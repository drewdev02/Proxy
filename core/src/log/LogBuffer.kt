package org.uci.proxy.core.log

import org.uci.proxy.core.model.LogEntry
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.uci.proxy.core.model.LogLevel

/**
 * Los últimos mensajes de registro, en memoria y para verlos en vivo. Al llenarse descarta los más antiguos.
 *
 * Es seguro llamarlo desde cualquier hilo: el motor del túnel escribe desde sus corrutinas y la pantalla lee desde la
 * interfaz.
 *
 * @param capacity cuántos mensajes se conservan.
 */
class LogBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "La capacidad debe ser positiva" }
    }

    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** El último id que tuvo el búfer antes de vaciarlo: los ids siguen creciendo después de [clear]. */
    @Volatile
    private var lastClearedId = -1L

    /** Los mensajes conservados, del más antiguo al más reciente. */
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    fun append(epochMillis: Long, level: LogLevel, tag: String, message: String) {
        val text = if (message.length > MAX_MESSAGE_LENGTH) message.take(MAX_MESSAGE_LENGTH) + "…" else message
        mutableEntries.update { current ->
            // El id sale de la lista misma, así que reintentar la actualización no deja huecos ni repite ids.
            val lastId = current.lastOrNull()?.id?.coerceAtLeast(lastClearedId) ?: lastClearedId
            val entry = LogEntry(id = lastId + 1, epochMillis = epochMillis, level = level, tag = tag, message = text)
            if (current.size < capacity) current + entry else current.drop(current.size - capacity + 1) + entry
        }
    }

    /**
     * Pone delante [older], los mensajes de una sesión anterior (del más antiguo al más reciente), sin pasar de la
     * capacidad. Llevan ids negativos, así que no se confunden con los de esta sesión ni cuentan como nuevos.
     */
    fun restore(older: List<LogEntry>) {
        mutableEntries.update { current ->
            val kept = older.takeLast((capacity - current.size).coerceAtLeast(0))
            kept.mapIndexed { index, entry -> entry.copy(id = (index - kept.size).toLong()) } + current
        }
    }

    /** Vacía el búfer. Los ids no vuelven a empezar: quien guarda lo nuevo por id ([LogRecorder]) sigue funcionando. */
    fun clear() {
        mutableEntries.update { current ->
            // Antes de publicar la lista vacía, para que un append que la vea ya sepa por dónde iban los ids.
            lastClearedId = maxOf(lastClearedId, current.lastOrNull()?.id ?: -1L)
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000

        /** Un mensaje enorme (una traza, por ejemplo) no debe ocupar memoria ni viajar entre procesos entero. */
        const val MAX_MESSAGE_LENGTH = 4_000
    }
}
