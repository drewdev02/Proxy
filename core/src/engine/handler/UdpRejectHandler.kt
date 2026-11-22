package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildIcmpPortUnreachable

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
