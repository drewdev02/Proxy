package org.uci.proxy.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState

class TunnelViewModel(
    private val control: TunnelControl,
    monitor: TunnelMonitor,
) : ViewModel() {
    /** False donde el túnel aún no existe: la pantalla no muestra entonces los controles de conexión. */
    val isAvailable: Boolean = control.isAvailable

    val state: StateFlow<TunnelState> = monitor.state

    fun connect(profileId: String) {
        viewModelScope.launch { control.connect(profileId) }
    }

    fun disconnect() {
        viewModelScope.launch { control.disconnect() }
    }
}
