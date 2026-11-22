package org.uci.proxy.profile

import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.routing.parseRule

/**
 * Las reglas de salida tal como se editan, más la regla que se está escribiendo.
 *
 * Todo lo que cambia el borrador son funciones puras que devuelven uno nuevo: el [ProfileEditorViewModel] solo decide
 * cuándo llamarlas y qué más reinicia (el aviso de guardado fallido, por ejemplo).
 */
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

    fun withEnabled(enabled: Boolean) = copy(rules = rules.copy(enabled = enabled))

    fun withDefaultExit(exit: RouteExit) = copy(rules = rules.copy(defaultExit = exit))

    /** Se está escribiendo la regla nueva: lo que antes se marcó como inválido ya no vale. */
    fun withNewRule(text: String) = copy(newRule = text, newRuleInvalid = false)

    fun withNewRuleExit(exit: RouteExit) = copy(newRuleExit = exit)

    /**
     * La regla que sale de lo escrito, o null si no es ni un dominio ni una IP. Si la salida elegida no la admite ese tipo
     * de regla (ver [RoutingRules.exitsFor]), pasa a «Proxy».
     */
    fun parsedNewRule(): RouteRule? {
        val parsed = parseRule(newRule, newRuleExit) ?: return null
        return if (parsed.exit in RoutingRules.exitsFor(parsed.kind)) parsed else parsed.copy(exit = RouteExit.PROXY)
    }

    fun markInvalid() = copy(newRuleInvalid = true)

    /**
     * Pone [rule] al final de la lista; si ya había una para lo mismo, se sustituye. Con [clearText] también vacía el
     * campo de la regla nueva (lo que se escribió ya está en la lista).
     */
    fun withRule(rule: RouteRule, clearText: Boolean): RoutingDraft = copy(
        rules = rules.copy(rules = rules.rules.filterNot { it.kind == rule.kind && it.value == rule.value } + rule),
        newRule = if (clearText) "" else newRule,
        newRuleInvalid = if (clearText) false else newRuleInvalid,
    )

    /** Una regla para la app [packageName], con la salida elegida para la regla nueva. */
    fun withAppRule(packageName: String): RoutingDraft =
        withRule(RouteRule(RuleKind.APP, packageName, newRuleExit), clearText = false)

    fun withRuleExit(index: Int, exit: RouteExit) = copy(
        rules = rules.copy(rules = rules.rules.mapIndexed { i, rule -> if (i == index) rule.copy(exit = exit) else rule }),
    )

    /** Sube ([delta] negativo) o baja una regla; un índice fuera de la lista no cambia nada. */
    fun withRuleMoved(index: Int, delta: Int): RoutingDraft {
        val target = index + delta
        if (index !in rules.rules.indices || target !in rules.rules.indices) return this
        val list = rules.rules.toMutableList()
        list.add(target, list.removeAt(index))
        return copy(rules = rules.copy(rules = list))
    }

    fun withRuleRemoved(index: Int) = copy(rules = rules.copy(rules = rules.rules.filterIndexed { i, _ -> i != index }))
}
