package org.uci.proxy.profile

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.RouteExit

/*
 * Lo que las secciones del editor pueden pedir, como contratos de la interfaz (nombres «on…», como recomienda Google
 * para los eventos de la UI). La pantalla de nivel superior los traduce a las funciones del ViewModel, que no se pasa
 * hacia abajo.
 */

/** Lo que la sección de reglas de salida puede pedir. */
interface RoutingActions {
    fun onRoutingEnabledChange(enabled: Boolean)

    fun onDefaultExitChange(exit: RouteExit)

    fun onNewRuleChange(text: String)

    fun onNewRuleExitChange(exit: RouteExit)

    fun addRule()

    /** Añade una regla para la app [packageName], con la salida elegida para la regla nueva. */
    fun addAppRule(packageName: String)

    fun onRuleExitChange(index: Int, exit: RouteExit)

    /** Sube ([delta] negativo) o baja una regla. */
    fun moveRule(index: Int, delta: Int)

    fun removeRule(index: Int)

    /** Para vistas previas. */
    object None : RoutingActions {
        override fun onRoutingEnabledChange(enabled: Boolean) = Unit
        override fun onDefaultExitChange(exit: RouteExit) = Unit
        override fun onNewRuleChange(text: String) = Unit
        override fun onNewRuleExitChange(exit: RouteExit) = Unit
        override fun addRule() = Unit
        override fun addAppRule(packageName: String) = Unit
        override fun onRuleExitChange(index: Int, exit: RouteExit) = Unit
        override fun moveRule(index: Int, delta: Int) = Unit
        override fun removeRule(index: Int) = Unit
    }
}

/** Lo que la sección «Otro País» puede pedir. */
interface CountryExitActions {
    fun onVpnEnabledChange(enabled: Boolean)

    fun onEngineChange(engine: CountryEngineKind)

    fun onVpnUsernameChange(username: String)

    fun onVpnPasswordChange(password: String)

    fun onTailscaleControlUrlChange(url: String)

    fun onTailscaleAuthKeyChange(key: String)

    fun onTailscaleExitNodeChange(node: String)

    fun onTailscaleHostnameChange(hostname: String)

    fun searchExitNodes()

    /** Para vistas previas. */
    object None : CountryExitActions {
        override fun onVpnEnabledChange(enabled: Boolean) = Unit
        override fun onEngineChange(engine: CountryEngineKind) = Unit
        override fun onVpnUsernameChange(username: String) = Unit
        override fun onVpnPasswordChange(password: String) = Unit
        override fun onTailscaleControlUrlChange(url: String) = Unit
        override fun onTailscaleAuthKeyChange(key: String) = Unit
        override fun onTailscaleExitNodeChange(node: String) = Unit
        override fun onTailscaleHostnameChange(hostname: String) = Unit
        override fun searchExitNodes() = Unit
    }
}
