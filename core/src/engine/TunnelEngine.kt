package org.uci.proxy.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildIcmpPortUnreachable
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PacketWriter
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.proxy.IpNamer
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.NameServiceReporter
import org.uci.proxy.core.tcp.TunTcpStack
import kotlin.time.TimeSource

/**
 * Atiende un tipo de tráfico. Añadir un protocolo es escribir otro manejador y registrarlo;
 * [TunnelEngine] no cambia.
 */
fun interface PacketHandler {
    /**
     * @return los paquetes que hay que devolver a las apps, o null si [packet] no es de este manejador.
     */
    suspend fun handle(packet: Ipv4Packet): List<ByteArray>?
}

/** Contesta las consultas DNS (UDP al puerto 53) con direcciones de la tabla falsa. */
class DnsPacketHandler(
    private val responder: DnsResponder,
    private val logger: Logger,
) : PacketHandler {
    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val datagram = UdpDatagram.parse(packet) ?: return null
        if (datagram.destinationPort != DNS_PORT) return null

        val query = datagram.payload
        val answer = responder.respond(query) ?: return emptyList()
        responder.parseQuestion(query)?.first?.let { question ->
            logger.log(LogLevel.DEBUG, TAG, "DNS tipo ${question.type}: ${question.name}")
        }
        // La respuesta vuelve desde el servidor al que se preguntó, hacia quien preguntó.
        return listOf(
            buildUdpPacket(
                source = packet.destination,
                destination = packet.source,
                sourcePort = DNS_PORT,
                destinationPort = datagram.sourcePort,
                payload = answer,
            ),
        )
    }

    private companion object {
        const val TAG = "Dns"
        const val DNS_PORT = 53
    }
}

/** Entrega cada segmento TCP a la pila, que responde por su cuenta a través de la interfaz. */
class TcpPacketHandler(
    private val stack: TunTcpStack,
    private val fakeDns: FakeDnsTable,
    /** El nombre de una IP real, si se conoce (la que dio el DNS de la VPN): así el proxy recibe el nombre y no la IP. */
    private val realHostFor: suspend (Int) -> String? = { null },
) : PacketHandler {
    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val segment = TcpSegment.parse(packet) ?: return null
        // La consulta a la tabla se hace aquí, en el bucle del motor, que es su único usuario.
        val host = fakeDns.hostFor(packet.destination)
            ?: if (fakeDns.contains(packet.destination)) null else realHostFor(packet.destination)
        if (host == null && fakeDns.contains(packet.destination)) {
            // Dirección inventada que ya no corresponde a ningún nombre (se recicló o es de otra sesión):
            // no hay a dónde conectar, así que se rechaza para que la app vuelva a resolver.
            return if (segment.has(TcpFlags.RST)) emptyList() else listOf(reject(packet, segment))
        }
        stack.process(packet, segment, destinationHost = host)
        return emptyList()
    }

    private fun reject(packet: Ipv4Packet, segment: TcpSegment): ByteArray {
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
}

/**
 * Rechaza el UDP que el túnel no puede transportar (todo salvo DNS) con un aviso ICMP, para que
 * las apps que lo intentan, como las que usan QUIC, pasen a TCP al instante en vez de esperar.
 */
class UdpRejectHandler : PacketHandler {
    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        UdpDatagram.parse(packet) ?: return null
        return listOfNotNull(buildIcmpPortUnreachable(packet))
    }
}

/** Crea el motor de una sesión, que depende del túnel y del perfil concretos. */
class TunnelEngineFactory(
    private val connectors: List<ProxyConnector>,
    private val randomBytes: RandomBytes,
    private val timeSource: TimeSource,
    private val logger: Logger,
    private val nameReporter: NameServiceReporter = NameServiceReporter.None,
    /** Qué app abrió cada conexión, para las reglas de app. */
    private val owner: ConnectionOwner = ConnectionOwner.Unknown,
) {
    /**
     * @param output interfaz por la que la pila TCP devuelve sus paquetes a las apps.
     * @param scope ámbito de la sesión: en él corren el temporizador TCP y los relés de cada conexión.
     */
    fun create(
        plan: TunAddressPlan,
        output: PacketWriter,
        scope: CoroutineScope,
        profile: ProxyProfile,
        password: String,
        settings: AppSettings = AppSettings(),
        /** Con reglas de salida: cómo repartir las conexiones. Sin ellas, todo va por el proxy. */
        split: SplitRouting? = null,
        /** El DNS de la red local, para abrir sin proxy lo que es interno; null si no se usa. */
        localDns: LocalDns? = null,
    ): TunnelEngine {
        val fakeDns = FakeDnsTable(plan.subnetBase)
        val connector = connectors.first { it.protocol == profile.protocol }
        val health = ProxyHealth(timeSource)
        val checks = ConnectivityChecks(settings.answerConnectivityChecks, health)
        val relay = TunnelRelay(connector, profile, password, logger, health, checks, IpNamer(settings, nameReporter, timeSource))

        val start = timeSource.markNow()
        val tcp = TunTcpStack(
            output = output,
            scope = scope,
            nowMs = { start.elapsedNow().inWholeMilliseconds },
            randomBytes = randomBytes,
            onAccept = relay::serve,
        )
        tcp.start()

        val dns = DnsPacketHandler(DnsResponder(fakeDns, checks::dnsAddressFor), logger)
        val requeued = Channel<Ipv4Packet>(REQUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
        val local = LocalDnsHandler(localDns, output, scope, logger) { requeued.trySend(it) }
        // El orden importa: DNS antes que el reparto por reglas, y este antes que el proxy y el rechazo genérico de UDP.
        // El de la red local va primero: lo interno no debe acabar ni en el DNS falso ni en el de la VPN.
        val handlers = if (split == null) {
            listOf(local, dns, TcpPacketHandler(tcp, fakeDns), UdpRejectHandler())
        } else {
            listOf(
                local,
                SplitDnsHandler(split, dns) { checks.dnsAddressFor(it) != null },
                ExitRouterHandler(split, fakeDns, logger, owner, scope),
                TcpPacketHandler(tcp, fakeDns, split.link::hostFor),
                UdpRejectHandler(),
            )
        }
        return TunnelEngine(handlers, requeued)
    }

    private companion object {
        const val REQUEUE_CAPACITY = 256
    }
}

/**
 * Bucle del túnel: lee cada paquete de la interfaz, se lo ofrece a los manejadores por orden
 * y escribe lo que el primero interesado devuelva. Lo que nadie reclama se descarta.
 *
 * @param requeued paquetes que un manejador apartó un momento y devuelve al bucle (ver [LocalDnsHandler]). Se atienden
 *   antes del siguiente paquete de la interfaz, en este mismo bucle: así los manejadores siguen sin compartir estado
 *   entre hilos.
 */
class TunnelEngine(
    private val handlers: List<PacketHandler>,
    private val requeued: ReceiveChannel<Ipv4Packet>? = null,
) {
    /** Corre hasta que la interfaz se cierra. */
    suspend fun run(tun: TunDevice) {
        while (true) {
            val bytes = tun.readPacket() ?: return
            while (true) {
                val waiting = requeued?.tryReceive()?.getOrNull() ?: break
                firstReply(waiting)?.forEach { tun.writePacket(it) }
            }
            val packet = Ipv4Packet.parse(bytes) ?: continue
            val replies = firstReply(packet) ?: continue
            replies.forEach { tun.writePacket(it) }
        }
    }

    private suspend fun firstReply(packet: Ipv4Packet): List<ByteArray>? {
        for (handler in handlers) handler.handle(packet)?.let { return it }
        return null
    }
}
