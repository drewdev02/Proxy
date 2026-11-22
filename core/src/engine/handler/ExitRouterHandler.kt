package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.engine.SplitRouting
import org.uci.proxy.core.engine.VpnLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildIcmpPortUnreachable
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.traffic.TrafficMeter

/**
 * Decide la salida de cada conexión TCP o UDP según las reglas. Lo que va por la VPN o se bloquea se resuelve aquí; lo que
 * va por el proxy sigue a los manejadores de siempre.
 *
 * La decisión se toma al abrirse la conexión (el SYN, o el primer datagrama UDP), que es cuando se pregunta qué app la
 * abrió, y vale para el resto de sus paquetes. Solo lo usa el bucle del motor, así que su tabla no necesita cerrojo.
 *
 * @param scope donde se resuelven por la VPN los nombres de las apps con regla «Otro País» que recibieron una dirección
 *   inventada. Mientras tanto sus paquetes se descartan: la app reintenta el SYN y para entonces ya hay IP real.
 */
class ExitRouterHandler(
    private val routing: SplitRouting,
    private val fakeDns: FakeDnsTable,
    private val logger: Logger,
    private val owner: ConnectionOwner = ConnectionOwner.Unknown,
    private val scope: CoroutineScope? = null,
    /** Dónde se cuenta lo que sale por la VPN; lo que vuelve lo cuenta [VpnLink]. */
    private val meter: TrafficMeter? = null,
) : PacketHandler {
    private data class Flow(val protocol: Int, val sourcePort: Int, val destination: Int, val destinationPort: Int)

    /** La salida de cada conexión, con el nombre de destino que tenía al decidirse. */
    private val flows = LinkedHashMap<Flow, Pair<String?, RouteExit>>()

    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val segment = TcpSegment.parse(packet)
        val datagram = if (segment == null) UdpDatagram.parse(packet) ?: return null else null
        val sourcePort = segment?.sourcePort ?: datagram!!.sourcePort
        val destinationPort = segment?.destinationPort ?: datagram!!.destinationPort

        val destination = packet.destination
        val fake = fakeDns.contains(destination)
        val host = if (fake) fakeDns.hostFor(destination) ?: return null else routing.link.hostFor(destination)
        val flow = Flow(packet.protocol, sourcePort, destination, destinationPort)
        val opening = if (segment != null) segment.has(TcpFlags.SYN) && !segment.has(TcpFlags.ACK) else flow !in flows
        val exit = decide(flow, packet, host, fake, opening)
        val target = "${host ?: formatIpv4(destination)}:$destinationPort"

        return when (exit) {
            RouteExit.VPN -> sendThroughVpn(packet, host, fake, opening, target)
            RouteExit.BLOCK -> {
                if (opening) logger.log(LogLevel.DEBUG, TAG, "Bloqueado: $target")
                listOfNotNull(if (segment != null) reset(packet, segment) else buildIcmpPortUnreachable(packet))
            }
            RouteExit.PROXY -> null
            // Lo directo de verdad (rangos, apps, y dominios con dirección interna) ni entra en la interfaz. Llega aquí un
            // dominio «Directo» que el DNS de la red no dio como interno: no hay otro camino que el proxy.
            RouteExit.DIRECT -> {
                if (opening) logger.log(LogLevel.DEBUG, TAG, "Directo sin dirección interna, por el proxy: $target")
                null
            }
        }
    }

    /** @return lo que se devuelve a la app (nada), o null si la conexión debe seguir por el proxy. */
    private suspend fun sendThroughVpn(packet: Ipv4Packet, host: String?, fake: Boolean, opening: Boolean, target: String): List<ByteArray>? {
        val link = routing.link
        if (!link.isUp) return null
        if (!fake) {
            if (!link.send(packet)) return null
            countSent(packet, host)
            if (opening) logger.log(LogLevel.DEBUG, TAG, "Otro País: $target")
            return emptyList()
        }
        // Dirección inventada: hace falta la IP real del nombre, que da el DNS de la VPN.
        val name = host ?: return null
        val real = link.addressFor(name)
        if (real != null) {
            if (!link.send(packet, destination = real)) return null
            countSent(packet, name)
            if (opening) logger.log(LogLevel.DEBUG, TAG, "Otro País: $target (${formatIpv4(real)})")
            return emptyList()
        }
        if (link.isUnresolvable(name) || scope == null) return null
        scope.launch { link.resolve(name) }
        return emptyList()
    }

    private suspend fun countSent(packet: Ipv4Packet, host: String?) {
        meter?.count(RouteExit.VPN, host ?: formatIpv4(packet.destination), app = null, up = packet.totalLength.toLong(), down = 0)
    }

    private fun decide(flow: Flow, packet: Ipv4Packet, host: String?, fake: Boolean, opening: Boolean): RouteExit {
        // Un SYN nuevo se decide otra vez: el mismo puerto puede reutilizarlo otra app.
        if (!opening) flows[flow]?.let { (knownHost, exit) -> if (knownHost == host) return exit }
        val rules = routing.rules
        // Solo al abrirse: preguntar al sistema por cada paquete sería caro, y la app no cambia.
        val apps = if (opening && rules.hasAppRules) owner.appsFor(flow.protocol, packet.source, flow.sourcePort, flow.destination, flow.destinationPort) else emptyList()
        val exit = rules.exitFor(host, address = if (fake) null else flow.destination, apps = apps)
        if (apps.isNotEmpty() && exit != rules.exitFor(host, address = if (fake) null else flow.destination)) {
            logger.log(LogLevel.DEBUG, TAG, "App ${apps.first()}: ${exit.name.lowercase()} hacia ${host ?: formatIpv4(flow.destination)}")
        }
        flows.remove(flow)
        if (flows.size >= MAX_FLOWS) flows.keys.firstOrNull()?.let(flows::remove)
        flows[flow] = host to exit
        return exit
    }

    private fun reset(packet: Ipv4Packet, segment: TcpSegment): ByteArray? {
        if (segment.has(TcpFlags.RST)) return null
        val consumed = segment.payloadLength + if (segment.has(TcpFlags.SYN)) 1 else 0
        return buildTcpPacket(
            source = packet.destination,
            destination = packet.source,
            sourcePort = segment.destinationPort,
            destinationPort = segment.sourcePort,
            sequence = if (segment.has(TcpFlags.ACK)) segment.acknowledgment else 0,
            acknowledgment = segment.sequence + consumed,
            flags = TcpFlags.RST or TcpFlags.ACK,
            window = 0,
        )
    }

    private companion object {
        const val TAG = "Rutas"
        const val MAX_FLOWS = 16384
    }
}
