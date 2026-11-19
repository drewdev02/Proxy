package org.uci.proxy.core.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.dns.dnsAnswerAddresses
import org.uci.proxy.core.dns.dnsTransactionId
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildIcmpPortUnreachable
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.rewriteAddresses
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.routing.RuleSet
import org.uci.proxy.core.vpn.VpnSession

/** Lo que el motor necesita para repartir las conexiones entre el proxy, la VPN y el bloqueo. */
class SplitRouting(val rules: RuleSet, val link: VpnLink)

/**
 * La VPN tal como la usa el motor con reglas de salida: puede no estar, caerse y volver.
 *
 * Las conexiones que van por ella salen de la interfaz con la dirección de la interfaz; aquí se les pone la que el
 * servidor VPN asignó, y a sus respuestas se les devuelve la de la interfaz. Como detrás de la interfaz solo hay un equipo,
 * basta con cambiar direcciones: los puertos ya son únicos.
 *
 * La usan a la vez el bucle del motor (lo que sale) y el de la VPN (lo que entra), así que su estado va bajo un cerrojo.
 *
 * @param appAddress la dirección de la interfaz, desde la que salen las apps.
 */
class VpnLink(private val appAddress: Int, private val logger: Logger) {
    private val lock = Mutex()
    private var session: VpnSession? = null
    private var vpnAddress = 0
    private var vpnDns = 0

    /** Nombre de cada IP real que dio el DNS de la VPN: así las reglas de dominio siguen valiendo al conectar. */
    private val hostByAddress = LinkedHashMap<Int, String>()

    /** Consultas DNS enviadas por la VPN, por puerto e identificador, con el servidor al que la app preguntó. */
    private val pendingDns = LinkedHashMap<Int, Int>()

    val isUp: Boolean get() = session != null

    suspend fun attach(newSession: VpnSession) = lock.withLock {
        session = newSession
        vpnAddress = parseIpv4(newSession.push.address) ?: 0
        vpnDns = newSession.push.dnsServers.firstNotNullOfOrNull(::parseIpv4) ?: FALLBACK_DNS
        pendingDns.clear()
    }

    suspend fun detach() = lock.withLock {
        session = null
        pendingDns.clear()
    }

    /** Cierra la sesión en curso sin esperar al cerrojo, para despertar su lectura al detener el túnel. */
    fun closeSession() {
        session?.close()
    }

    suspend fun hostFor(address: Int): String? = lock.withLock { hostByAddress[address] }

    /** Manda [packet] por la VPN. @return false si la VPN no está, y entonces la conexión debe ir por otro lado. */
    suspend fun send(packet: Ipv4Packet): Boolean {
        val (current, translated) = lock.withLock {
            val current = session ?: return false
            current to rewriteAddresses(packet, vpnAddress, packet.destination)
        }
        current.writePacket(translated)
        return true
    }

    /**
     * Pregunta [packet] (una consulta DNS de una app) al DNS de la VPN, para que la app reciba la IP real y su conexión
     * pueda salir por la VPN. @return false si la VPN no está.
     */
    suspend fun sendDnsQuery(packet: Ipv4Packet, datagram: UdpDatagram, host: String): Boolean {
        val id = dnsTransactionId(datagram.payload) ?: return false
        val (current, translated) = lock.withLock {
            val current = session ?: return false
            remember(pendingDns, (datagram.sourcePort shl 16) or id, packet.destination, MAX_PENDING_DNS)
            current to rewriteAddresses(packet, vpnAddress, vpnDns)
        }
        logger.log(LogLevel.DEBUG, TAG, "DNS por Otro País: $host")
        current.writePacket(translated)
        return true
    }

    /**
     * Un paquete que llega de la VPN, ya con las direcciones que la app espera.
     *
     * @return null si no es para nosotros.
     */
    suspend fun inbound(bytes: ByteArray): ByteArray? {
        val packet = Ipv4Packet.parse(bytes) ?: return null
        return lock.withLock {
            if (session == null || packet.destination != vpnAddress) return@withLock null
            val datagram = if (packet.source == vpnDns) UdpDatagram.parse(packet) else null
            val askedServer = datagram?.takeIf { it.sourcePort == DNS_PORT }?.let { reply ->
                val payload = reply.payload
                val id = dnsTransactionId(payload) ?: return@let null
                pendingDns.remove((reply.destinationPort shl 16) or id)?.also {
                    learn(payload)
                }
            }
            rewriteAddresses(packet, askedServer ?: packet.source, appAddress)
        }
    }

    /** Apunta el nombre de cada IP de la respuesta. Se llama con el cerrojo tomado. */
    private fun learn(answer: ByteArray) {
        val name = DnsResponder.questionName(answer) ?: return
        dnsAnswerAddresses(answer).forEach { remember(hostByAddress, it, name, MAX_HOSTS) }
    }

    /** Guarda en un mapa acotado: lleno, se olvida lo más antiguo. */
    private fun <V> remember(map: LinkedHashMap<Int, V>, key: Int, value: V, max: Int) {
        map.remove(key)
        if (map.size >= max) map.keys.firstOrNull()?.let(map::remove)
        map[key] = value
    }

    private companion object {
        const val TAG = "Vpn"
        const val DNS_PORT = 53
        const val MAX_HOSTS = 8192
        const val MAX_PENDING_DNS = 1024

        // 1.1.1.1, si el servidor no empuja un DNS: de todos modos va por la VPN.
        const val FALLBACK_DNS = (1 shl 24) or (1 shl 16) or (1 shl 8) or 1
    }
}

/**
 * Con reglas de salida, las consultas DNS de nombres que salen por la VPN se preguntan al DNS de la VPN: la app recibe la
 * IP real y su conexión puede ir por la VPN tal cual. El resto se contesta con el DNS falso, como siempre.
 */
class SplitDnsHandler(
    private val routing: SplitRouting,
    private val fallback: PacketHandler,
    /** Los nombres que el túnel contesta él mismo (las comprobaciones de conectividad) nunca van a la VPN. */
    private val hasOverride: (String) -> Boolean,
) : PacketHandler {
    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val datagram = UdpDatagram.parse(packet) ?: return null
        if (datagram.destinationPort != DNS_PORT) return null
        val question = DnsResponder.parseQuestionOf(datagram.payload)
        if (question != null && question.type == DnsResponder.TYPE_A && !hasOverride(question.name) &&
            routing.rules.exitFor(question.name, address = null) == RouteExit.VPN &&
            routing.link.sendDnsQuery(packet, datagram, question.name)
        ) {
            return emptyList()
        }
        return fallback.handle(packet)
    }

    private companion object {
        const val DNS_PORT = 53
    }
}

/**
 * Decide la salida de cada paquete TCP o UDP según las reglas. Lo que va por la VPN o se bloquea se resuelve aquí; lo que
 * va por el proxy sigue a los manejadores de siempre.
 *
 * Solo lo usa el bucle del motor, así que su caché no necesita cerrojo.
 */
class ExitRouterHandler(
    private val routing: SplitRouting,
    private val fakeDns: FakeDnsTable,
    private val logger: Logger,
) : PacketHandler {
    /** La última decisión para cada destino, con el nombre que tenía: las reglas no cambian durante la sesión. */
    private val decisions = HashMap<Int, Pair<String?, RouteExit>>()

    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val segment = TcpSegment.parse(packet)
        if (segment == null && UdpDatagram.parse(packet) == null) return null

        val destination = packet.destination
        val fake = fakeDns.contains(destination)
        val host = if (fake) fakeDns.hostFor(destination) ?: return null else routing.link.hostFor(destination)
        val exit = decide(destination, host, fake)
        val opening = segment != null && segment.has(TcpFlags.SYN) && !segment.has(TcpFlags.ACK)

        return when (exit) {
            // Una dirección inventada no se puede llevar por la VPN (no es la real): esa conexión sigue por el proxy.
            RouteExit.VPN -> if (!fake && routing.link.send(packet)) {
                if (opening) logger.log(LogLevel.DEBUG, TAG, "Otro País: ${host ?: formatIpv4(destination)}:${segment.destinationPort}")
                emptyList()
            } else {
                null
            }
            RouteExit.BLOCK -> {
                if (opening) logger.log(LogLevel.DEBUG, TAG, "Bloqueado: ${host ?: formatIpv4(destination)}:${segment.destinationPort}")
                listOfNotNull(if (segment != null) reset(packet, segment) else buildIcmpPortUnreachable(packet))
            }
            // Directo solo existe para rangos de IP, que ni entran en la interfaz; un dominio con esa salida va por el proxy.
            RouteExit.PROXY, RouteExit.DIRECT -> null
        }
    }

    private fun decide(destination: Int, host: String?, fake: Boolean): RouteExit {
        decisions[destination]?.let { (knownHost, exit) -> if (knownHost == host) return exit }
        val exit = routing.rules.exitFor(host, address = if (fake) null else destination)
        if (decisions.size >= MAX_DECISIONS) decisions.clear()
        decisions[destination] = host to exit
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
        const val MAX_DECISIONS = 4096
    }
}
