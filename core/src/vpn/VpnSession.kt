package org.uci.proxy.core.vpn

import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.TunnelFailure

/**
 * La red que un motor de «Otro País» le da al túnel, sin detalles del protocolo: la dirección de la interfaz con su
 * prefijo (la red del motor, donde suele vivir su DNS), los servidores DNS y la MTU.
 */
data class VpnNetwork(
    val address: String,
    val prefixLength: Int,
    val dnsServers: List<String>,
    val mtu: Int,
) {
    /** La máscara en notación de puntos, como la piden las interfaces. */
    val netmask: String get() = formatIpv4(maskOf(prefixLength))

    /** La red del motor (por ejemplo 10.98.0.0/16): debe alcanzarse por la interfaz, porque ahí vive su DNS. */
    val subnet: Ipv4Route?
        get() {
            val ip = parseIpv4(address) ?: return null
            return Ipv4Route(ip and maskOf(prefixLength), prefixLength)
        }

    companion object {
        fun maskOf(prefix: Int): Int = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))

        /** El prefijo de una máscara en notación de puntos (255.255.255.0 → 24). */
        fun prefixOf(netmask: String): Int = parseIpv4(netmask)?.countOneBits() ?: 24
    }
}

/** Una sesión de «Otro País» lista: lo que el túnel necesita para pasar paquetes IP. */
interface VpnSession {
    val network: VpnNetwork

    suspend fun writePacket(packet: ByteArray)

    /** Entrega cada paquete IP que llega del motor hasta que la sesión termina. */
    suspend fun receive(onPacket: suspend (ByteArray) -> Unit)

    fun close()
}

sealed interface VpnConnectResult {
    class Connected(val session: VpnSession) : VpnConnectResult

    data class Failed(val failure: TunnelFailure) : VpnConnectResult
}
