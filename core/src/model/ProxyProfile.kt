package org.uci.proxy.core.model

import kotlinx.serialization.SerialName
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
    /**
     * Si no es null, el tráfico sale por «Otro País» con este motor, encadenado tras este proxy. En el JSON sigue
     * llamándose `vpn`, como cuando solo existía OpenVPN, para que los perfiles guardados se lean igual.
     */
    @SerialName("vpn")
    val countryExit: CountryExit? = null,
    /** Qué sale por el proxy, por Otro País, directo o se bloquea. */
    val routing: RoutingRules = RoutingRules(),
)
