package org.uci.proxy.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogLevel

data class LogsUiState(
    /** Los mensajes que se muestran: ya filtrados por [minLevel]. */
    val entries: List<LogEntry> = emptyList(),
    /** Se muestran los mensajes de este nivel y de los más graves. */
    val minLevel: LogLevel = LogLevel.DEBUG,
    val paused: Boolean = false,
    /** Cuántos mensajes hay en total, con o sin filtro. */
    val total: Int = 0,
)

/**
 * El registro en vivo. Con la pantalla en pausa la lista se queda como estaba para poder leerla, mientras el búfer sigue
 * recibiendo mensajes; al reanudar se ve todo lo que llegó entretanto.
 */
class LogsViewModel(private val buffer: LogBuffer) : ViewModel() {
    private val minLevel = MutableStateFlow(LogLevel.DEBUG)

    /** La foto de la lista en el momento de pausar, o null si no está en pausa. */
    private val frozen = MutableStateFlow<List<LogEntry>?>(null)

    val uiState: StateFlow<LogsUiState> = combine(buffer.entries, minLevel, frozen) { live, level, snapshot ->
        val source = snapshot ?: live
        LogsUiState(
            entries = source.filter { it.level >= level },
            minLevel = level,
            paused = snapshot != null,
            total = source.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LogsUiState())

    fun onLevelChange(level: LogLevel) {
        minLevel.value = level
    }

    fun onTogglePause() {
        frozen.value = if (frozen.value == null) buffer.entries.value else null
    }

    fun onClear() {
        buffer.clear()
        // En pausa, la foto es lo que se ve: si no se vaciara también, «Limpiar» no haría nada a la vista.
        if (frozen.value != null) frozen.value = emptyList()
    }

    /** Lo que hay en pantalla, como texto, para copiarlo. */
    fun textToCopy(): String = uiState.value.entries.toText()
}
