package org.uci.proxy.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState

/** Para las plataformas donde el túnel todavía no está implementado: la interfaz oculta sus controles. */
class UnavailableTunnel : TunnelControl, TunnelMonitor {
    override val isAvailable = false
    override val state: StateFlow<TunnelState> = MutableStateFlow(TunnelState.Disconnected)

    override suspend fun connect(profileId: String) = Unit

    override suspend fun disconnect() = Unit
}
