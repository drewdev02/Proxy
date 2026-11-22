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
import org.uci.proxy.core.log.LogRecorder
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
class LogsViewModel(
    private val buffer: LogBuffer,
    private val recorder: LogRecorder,
) : ViewModel() {
    private val minLevel = MutableStateFlow(LogLevel.DEBUG)

    /** La foto de la lista en el momento de pausar, o null si no está en pausa. */
    private val frozen = MutableStateFlow<List<LogEntry>?>(null)

    val uiState: StateFlow<LogsUiState> = combine(buffer.entries, minLevel, frozen, ::buildState).stateIn(
        viewModelScope,
        // Solo mientras alguien lo mira, y 5 s más para no reiniciar al girar la pantalla (recomendación de Google).
        SharingStarted.WhileSubscribed(5_000),
        buildState(buffer.entries.value, minLevel.value, frozen.value),
    )

    private fun buildState(live: List<LogEntry>, level: LogLevel, snapshot: List<LogEntry>?): LogsUiState {
        val source = snapshot ?: live
        return LogsUiState(
            entries = source.filter { it.level >= level },
            minLevel = level,
            paused = snapshot != null,
            total = source.size,
        )
    }

    fun setMinLevel(level: LogLevel) {
        minLevel.value = level
    }

    fun togglePause() {
        frozen.value = if (frozen.value == null) buffer.entries.value else null
    }

    fun clear() {
        // También lo guardado en disco: si no, volvería a aparecer al abrir la app otra vez.
        recorder.clear()
        // En pausa, la foto es lo que se ve: si no se vaciara también, «Limpiar» no haría nada a la vista.
        if (frozen.value != null) frozen.value = emptyList()
    }

    /** Lo que hay en pantalla, como texto, para copiarlo. */
    fun textToCopy(): String = buildState(buffer.entries.value, minLevel.value, frozen.value).entries.toText()
}
