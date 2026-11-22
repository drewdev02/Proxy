package org.uci.proxy.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState

/** Estado del túnel, compartido entre quien lo ejecuta y quien lo observa dentro del mismo proceso. */
class TunnelStateStore : TunnelMonitor {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    fun set(newState: TunnelState) {
        mutableState.value = newState
    }

    /** Cambia el estado a partir del actual sin perder un cambio que llegue a la vez desde otra corrutina. */
    fun update(change: (TunnelState) -> TunnelState) {
        mutableState.update(change)
    }
}
