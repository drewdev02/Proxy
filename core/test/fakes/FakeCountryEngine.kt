package org.uci.proxy.core.fakes

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.vpn.CountryEngine
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.ExitContext
import org.uci.proxy.core.vpn.VpnConnectResult

/** Un motor de Otro País que responde lo que diga la prueba. */
class FakeCountryEngine(
    override val kind: CountryEngineKind = CountryEngineKind.OPENVPN,
    override val isAvailable: Boolean = true,
    private val onConnect: suspend (ExitContext, CountryExitStart) -> VpnConnectResult,
) : CountryEngine {
    override suspend fun connect(context: ExitContext, start: CountryExitStart): VpnConnectResult = onConnect(context, start)
}

/** Los motores de una prueba: uno solo, del tipo indicado. */
fun fakeEngines(
    kind: CountryEngineKind = CountryEngineKind.OPENVPN,
    onConnect: suspend (ExitContext, CountryExitStart) -> VpnConnectResult,
) = CountryEngines(listOf(FakeCountryEngine(kind, onConnect = onConnect)))
