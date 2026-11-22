package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.tcp.TunTcpStack

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
