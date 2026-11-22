package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger

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
