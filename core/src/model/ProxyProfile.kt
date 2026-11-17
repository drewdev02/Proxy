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
)
