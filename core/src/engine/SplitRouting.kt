package org.uci.proxy.core.engine

import org.uci.proxy.core.routing.RuleSet

/** Lo que el motor necesita para repartir las conexiones entre el proxy, la VPN y el bloqueo. */
class SplitRouting(val rules: RuleSet, val link: VpnLink)
