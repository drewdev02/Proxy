package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

/** Por dónde sale una conexión. */
@Serializable
enum class RouteExit {
    /** El proxy del perfil, como siempre. */
    PROXY,

    /** La VPN del perfil («Otro País»). Sin VPN configurada o con ella caída, sale por el proxy. */
    VPN,

    /**
     * La red real, sin proxy ni túnel. Solo en reglas de IP o rango (esos destinos no entran en el túnel) y de app (en
     * Android la app queda fuera de la VPN).
     */
    DIRECT,

    /** No sale: la conexión se rechaza al instante. */
    BLOCK,
}

@Serializable
enum class RuleKind {
    /** Un dominio y sus subdominios (`netflix.com`), o un patrón con `*` (`*.google.*`). */
    DOMAIN,

    /** Una IP (`1.2.3.4`) o un rango CIDR (`149.22.0.0/16`). */
    IP,

    /** El paquete de una app de Android (`com.netflix.mediaclient`). En escritorio aún no se aplica. */
    APP,
}

@Serializable
data class RouteRule(val kind: RuleKind, val value: String, val exit: RouteExit)

/**
 * Las reglas de salida de un perfil. Apagadas, el perfil se comporta como siempre: todo por el proxy, o todo por la VPN si
 * tiene una. Encendidas, cada conexión toma la salida de la primera regla que coincide, o [defaultExit] si ninguna.
 */
@Serializable
data class RoutingRules(
    val enabled: Boolean = false,
    val defaultExit: RouteExit = RouteExit.PROXY,
    val rules: List<RouteRule> = emptyList(),
) {
    /** Si alguna conexión puede salir por la VPN, y por tanto hay que abrirla. */
    val usesVpn: Boolean get() = enabled && (defaultExit == RouteExit.VPN || rules.any { it.exit == RouteExit.VPN })

    companion object {
        /** Las salidas que admite cada tipo de regla. */
        fun exitsFor(kind: RuleKind): List<RouteExit> = when (kind) {
            RuleKind.IP, RuleKind.APP -> RouteExit.entries
            RuleKind.DOMAIN -> listOf(RouteExit.PROXY, RouteExit.VPN, RouteExit.BLOCK)
        }

        /** Las que puede tener [defaultExit]. */
        val DEFAULT_EXITS = listOf(RouteExit.PROXY, RouteExit.VPN)
    }
}
