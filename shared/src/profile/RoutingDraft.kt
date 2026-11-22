package org.uci.proxy.profile

import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RoutingRules

/** Las reglas de salida tal como se editan, más la regla que se está escribiendo. */
data class RoutingDraft(
    val rules: RoutingRules = RoutingRules(),
    val newRule: String = "",
    /** Proxy por defecto: un perfil nuevo no tiene Otro País (ver [withNewRuleExitFor]). */
    val newRuleExit: RouteExit = RouteExit.PROXY,
    /** Lo escrito no es ni un dominio ni una IP. */
    val newRuleInvalid: Boolean = false,
) {
    /**
     * La salida de la regla nueva sigue a Otro País: al apagarlo deja de ser «Otro País» (no se puede elegir) y al
     * encenderlo pasa a serlo, que es para lo que se suelen añadir reglas. Otras salidas elegidas a mano se respetan.
     */
    fun withNewRuleExitFor(countryExitEnabled: Boolean): RoutingDraft = when {
        !countryExitEnabled && newRuleExit == RouteExit.VPN -> copy(newRuleExit = RouteExit.PROXY)
        countryExitEnabled && newRuleExit == RouteExit.PROXY -> copy(newRuleExit = RouteExit.VPN)
        else -> this
    }
}
