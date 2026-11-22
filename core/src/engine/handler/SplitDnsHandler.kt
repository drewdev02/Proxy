package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.engine.SplitRouting
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.UdpDatagram

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
            routing.rules.exitFor(question.name, address = null) == RouteExit.VPN
        ) {
            // Sin VPN y sin vuelta al proxy no se contesta: un DNS falso llevaría la conexión al proxy.
            if (routing.link.sendDnsQuery(packet, datagram, question.name) || !routing.fallbackToProxy) return emptyList()
        }
        return fallback.handle(packet)
    }

    private companion object {
        const val DNS_PORT = 53
    }
}
