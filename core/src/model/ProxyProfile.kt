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
    /**
     * Si se consulta la cuota de navegación de la UCI con el usuario y la contraseña del perfil. Null: lo que corresponda
     * al proxy (sí, si es de la UCI). Los perfiles guardados antes no lo tienen y quedan así.
     */
    val uciQuota: Boolean? = null,
) {
    /** Si el proxy es de la UCI: `uci.cu` o uno de sus nombres. */
    val isUciProxy: Boolean get() = host.lowercase().let { it == UCI_DOMAIN || it.endsWith(".$UCI_DOMAIN") }

    /** Si hay que consultar la cuota de la UCI con este perfil. */
    val checksUciQuota: Boolean get() = (uciQuota ?: isUciProxy) && username.isNotBlank()

    /** El usuario tal como lo pide el servicio de cuotas: sin el dominio (`UCI\ana`, `ana@uci.cu` → `ana`). */
    val uciAccount: String get() = username.trim().substringAfterLast('\\').substringBefore('@')

    companion object {
        const val UCI_DOMAIN = "uci.cu"
    }
}
