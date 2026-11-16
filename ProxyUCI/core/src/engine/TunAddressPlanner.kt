package org.uci.proxy.core.engine

import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.port.LocalAddresses
import org.uci.proxy.core.port.RandomBytes

/** Direcciones elegidas para un túnel. */
data class TunAddressPlan(val subnetBase: Int) {
    /** Dirección de la interfaz: x.x.0.1. */
    val tunAddress: Int get() = subnetBase or 1

    /** Servidor DNS anunciado a las apps: x.x.0.2. */
    val dnsServer: Int get() = subnetBase or 2

    /** La subred virtual 10.A.0.0/16, de donde salen las direcciones del DNS falso. */
    val subnet: Ipv4Route get() = Ipv4Route(subnetBase, SUBNET_PREFIX)

    val subnetText: String get() = formatIpv4(subnetBase)
    val tunAddressText: String get() = formatIpv4(tunAddress)
    val dnsServerText: String get() = formatIpv4(dnsServer)

    private companion object {
        const val SUBNET_PREFIX = 16
    }
}

/**
 * Elige la subred virtual 10.A.0.0/16 del túnel.
 *
 * Las redes institucionales suelen ser 10.x, así que A se escoge distinto del segundo octeto
 * de cualquier dirección 10.x que ya tenga el equipo: de otro modo el túnel taparía la red real.
 */
class TunAddressPlanner(
    private val localAddresses: LocalAddresses,
    private val randomBytes: RandomBytes,
) {
    fun plan(): TunAddressPlan {
        val taken = localAddresses.ipv4()
            .filter { it ushr 24 == NETWORK_10 }
            .map { (it ushr 16) and 0xFF }
            .toSet()
        val free = (1..254).filter { it !in taken }
        // 254 valores posibles frente a un puñado de interfaces: siempre queda alguno libre.
        val secondOctet = free[(randomBytes.next(1)[0].toInt() and 0xFF) % free.size]
        return TunAddressPlan(subnetBase = (NETWORK_10 shl 24) or (secondOctet shl 16))
    }

    private companion object {
        const val NETWORK_10 = 10
    }
}
