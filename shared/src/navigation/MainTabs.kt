package org.uci.proxy.navigation

import androidx.navigation3.runtime.NavKey

/*
 * La navegación principal: cuatro pestañas (Conexión, Registros, Perfiles, Ajustes) y, encima de ellas, pantallas de
 * detalle como el editor de perfil. La pila siempre empieza por Conexión, así que «atrás» desde otra pestaña vuelve a
 * Conexión y desde Conexión sale de la app.
 */

/** Las pestañas, en el orden de la barra. */
internal val mainTabs: List<Route> = listOf(Route.Connection, Route.Logs, Route.ProfileList, Route.Settings)

/** Deja la pila en [tab] (sobre Conexión), descartando las pantallas de detalle abiertas. */
internal fun MutableList<NavKey>.selectTab(tab: Route) {
    require(tab in mainTabs) { "$tab no es una pestaña" }
    clear()
    add(Route.Connection)
    if (tab != Route.Connection) add(tab)
}

/** La pestaña a la que pertenece lo que se ve: la última de la pila. */
internal fun List<NavKey>.currentTab(): Route = lastOrNull { it in mainTabs } as? Route ?: Route.Connection

/** Si lo que se ve es una pestaña (con barra) y no una pantalla de detalle. */
internal fun List<NavKey>.isOnTab(): Boolean = lastOrNull() in mainTabs
