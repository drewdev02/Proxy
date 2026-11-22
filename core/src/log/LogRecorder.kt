package org.uci.proxy.core.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.port.LogHistory
import org.uci.proxy.core.common.AppDispatchers
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Guarda en [history] lo que llega a [buffer], por lotes, y al arrancar recupera en el búfer lo de la sesión anterior.
 *
 * Solo lo usa el proceso de la interfaz: en macOS y Windows el motor del túnel es otro proceso (con privilegios) y sus
 * mensajes ya llegan a este búfer por el canal del motor. Así un único proceso escribe en la base.
 *
 * No registra sus propios fallos: lo haría en el mismo búfer que intenta guardar. Si guardar falla, lo pendiente se
 * reintenta en la siguiente vuelta.
 */
class LogRecorder(
    private val buffer: LogBuffer,
    private val history: LogHistory,
    private val clock: Clock,
    dispatchers: AppDispatchers,
    private val saveInterval: Duration = 2.seconds,
    private val retention: Duration = 7.days,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.background)
    private val lock = Mutex()
    private var lastSavedId = -1L
    private var job: Job? = null

    /** Empieza a guardar. Llamarlo otra vez no hace nada. */
    fun start(): Job = job ?: scope.launch {
        lock.withLock {
            attempt {
                prune()
                buffer.restore(history.latest(buffer.capacity))
            }
        }
        var lastPrune = clock.now()
        while (isActive) {
            delay(saveInterval)
            saveNew()
            if (clock.now() - lastPrune >= PRUNE_INTERVAL) {
                lock.withLock { attempt { prune() } }
                lastPrune = clock.now()
            }
        }
    }.also { job = it }

    /** Guarda ya lo pendiente, sin esperar a la siguiente vuelta. */
    suspend fun saveNew() = lock.withLock {
        val pending = buffer.entries.value.filter { it.id > lastSavedId }
        if (pending.isEmpty()) return@withLock
        attempt {
            history.append(pending)
            lastSavedId = pending.last().id
        }
    }

    /** Vacía el registro en pantalla al momento y, enseguida, también el guardado. */
    fun clear(): Job {
        val clearedAt = clock.now().toEpochMilliseconds()
        buffer.clear()
        // Con el cerrojo: un lote leído antes de vaciar y escrito después también se borra.
        return scope.launch { lock.withLock { attempt { history.deleteBefore(clearedAt + 1) } } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun prune() {
        history.deleteBefore((clock.now() - retention).toEpochMilliseconds())
        history.trimTo(maxEntries)
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ver la documentación de la clase: se reintenta en la siguiente vuelta.
        }
    }

    companion object {
        /** Unas pocas megas en disco como mucho. */
        const val MAX_ENTRIES = 20_000
        private val PRUNE_INTERVAL = 1.hours
    }
}
