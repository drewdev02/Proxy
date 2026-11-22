package org.uci.proxy.core.vpn

import org.uci.proxy.core.model.VpnHandshakeIssue

/** Lo que el servidor VPN indica al cliente en su `PUSH_REPLY`. */
class OpenVpnPush(
    /** Dirección de la interfaz y máscara. */
    val address: String,
    val netmask: String,
    val dnsServers: List<String>,
    val peerId: Int,
    val cipher: String,
    val tunMtu: Int,
    val pingSeconds: Int,
    val pingRestartSeconds: Int,
    /** True si el servidor manda todo el tráfico por la VPN (`redirect-gateway`). */
    val redirectGateway: Boolean,
)

internal class AuthFailedException(message: String) : Exception(message)

/** Interpreta `PUSH_REPLY,opción,opción,...`. @throws OpenVpnException si falta algo imprescindible. */
internal fun parsePushReply(message: String): OpenVpnPush {
    val options = message.removePrefix("PUSH_REPLY").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun value(name: String) = options.firstOrNull { it == name || it.startsWith("$name ") }

    val ifconfig = value("ifconfig")?.split(' ') ?: throw OpenVpnException(VpnHandshakeIssue.UNSUPPORTED_SERVER, "El servidor VPN no indicó una dirección (ifconfig)")
    val address = ifconfig.getOrNull(1) ?: throw OpenVpnException(VpnHandshakeIssue.UNSUPPORTED_SERVER, "El servidor VPN envió un ifconfig incompleto")
    // Con `topology subnet` el segundo valor es la máscara; con `net30` o `p2p` es la dirección del otro extremo.
    val second = ifconfig.getOrNull(2)
    val netmask = if (value("topology")?.endsWith("subnet") == true && second != null) second else "255.255.255.0"

    val peerId = value("peer-id")?.split(' ')?.getOrNull(1)?.toIntOrNull()
        ?: throw OpenVpnException(VpnHandshakeIssue.UNSUPPORTED_SERVER, "El servidor VPN no indicó un peer-id: solo se admite el formato de datos v2", subject = "peer-id")
    return OpenVpnPush(
        address = address,
        netmask = netmask,
        dnsServers = options.filter { it.startsWith("dhcp-option DNS ") }.map { it.removePrefix("dhcp-option DNS ").trim() },
        peerId = peerId,
        cipher = value("cipher")?.split(' ')?.getOrNull(1) ?: "AES-256-GCM",
        tunMtu = value("tun-mtu")?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 1500,
        pingSeconds = value("ping")?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 10,
        pingRestartSeconds = value("ping-restart")?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 60,
        redirectGateway = options.any { it.startsWith("redirect-gateway") },
    )
}
