package org.uci.proxy.core.engine

import org.uci.proxy.core.routing.RuleSet

/**
 * Lo que el motor necesita para repartir las conexiones entre el proxy, la VPN y el bloqueo.
 *
 * @param fallbackToProxy si lo que va por la VPN sale por el proxy mientras está caída; si no, se bloquea.
 */
class SplitRouting(val rules: RuleSet, val link: VpnLink, val fallbackToProxy: Boolean = link.fallbackToProxy)
