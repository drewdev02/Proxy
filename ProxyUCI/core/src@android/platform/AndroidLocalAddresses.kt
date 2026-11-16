package org.uci.proxy.core.platform

import org.uci.proxy.core.port.LocalAddresses
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

class AndroidLocalAddresses : LocalAddresses {
    override fun ipv4(): List<Int> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { address -> address.address.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) } }
    } catch (_: SocketException) {
        // Sin acceso a las interfaces no hay nada que evitar: se elige la subred al azar.
        emptyList()
    }
}
