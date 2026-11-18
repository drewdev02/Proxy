package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProxyProtocol { HTTP, SOCKS5 }

/**
 * Perfil de un proxy. La contraseña no forma parte del perfil: se guarda aparte en el
 * almacén seguro de la plataforma y nunca se serializa junto a estos datos.
 */
@Serializable
data class ProxyProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val username: String,
    /** Si no es null, el tráfico sale por una VPN OpenVPN (modo «Otro País») encadenada tras este proxy. */
    val vpn: VpnProfile? = null,
)

/**
 * Lo no sensible de la VPN de un perfil. El archivo `.ovpn` (que lleva la clave `tls-crypt`) y la contraseña de la VPN
 * se guardan en el almacén seguro, junto a la contraseña del proxy.
 */
@Serializable
data class VpnProfile(
    /** Nombre del archivo importado, solo para mostrarlo. */
    val fileName: String,
    val username: String,
)

/** Los secretos de la VPN de un perfil. */
@Serializable
data class VpnSecrets(val ovpn: String, val password: String)
