package org.uci.proxy.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.uci.proxy.core.port.LogLevel

/**
 * Una línea del registro. [id] crece de uno en uno dentro de un [LogBuffer] y sirve para saber qué es nuevo y como clave
 * de la lista en pantalla; no tiene sentido fuera del búfer que lo asignó.
 */
data class LogEntry(
    val id: Long,
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * Los últimos mensajes de registro, en memoria y para verlos en vivo. Al llenarse descarta los más antiguos.
 *
 * Es seguro llamarlo desde cualquier hilo: el motor del túnel escribe desde sus corrutinas y la pantalla lee desde la
 * interfaz.
 *
 * @param capacity cuántos mensajes se conservan.
 */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "La capacidad debe ser positiva" }
    }

    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Los mensajes conservados, del más antiguo al más reciente. */
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    fun append(epochMillis: Long, level: LogLevel, tag: String, message: String) {
        val text = if (message.length > MAX_MESSAGE_LENGTH) message.take(MAX_MESSAGE_LENGTH) + "…" else message
        mutableEntries.update { current ->
            // El id sale de la lista misma, así que reintentar la actualización no deja huecos ni repite ids.
            val entry = LogEntry(id = (current.lastOrNull()?.id ?: -1L) + 1, epochMillis = epochMillis, level = level, tag = tag, message = text)
            if (current.size < capacity) current + entry else current.drop(current.size - capacity + 1) + entry
        }
    }

    /** Vacía el búfer. Los ids vuelven a empezar: nada de lo que se muestre después comparte id con lo anterior. */
    fun clear() {
        mutableEntries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000

        /** Un mensaje enorme (una traza, por ejemplo) no debe ocupar memoria ni viajar entre procesos entero. */
        const val MAX_MESSAGE_LENGTH = 4_000
    }
}
