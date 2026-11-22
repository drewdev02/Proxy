package org.uci.proxy.core.traffic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.common.AppDispatchers
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Lo que salió por [exit] un día. [day] es el día local contado desde el 1 de enero de 1970. */
data class DailyTraffic(val day: Int, val exit: RouteExit, val up: Long, val down: Long) {
    val total: Long get() = up + down
}

/** Lo consumido por un destino o una app. [name] vacío: sin detalle (todo por Otro País, sin reglas). */
data class NamedTraffic(val name: String, val up: Long, val down: Long) {
    val total: Long get() = up + down
}

/** El consumo guardado en disco, sumado por día, salida, destino y app. */
interface TrafficHistory {
    /** Suma [usage] a lo que ya hubiera de [day]. */
    suspend fun add(day: Int, usage: List<TrafficUsage>)

    /** Lo de cada día y salida desde [fromDay] (incluido). */
    fun daily(fromDay: Int): Flow<List<DailyTraffic>>

    /** Los destinos que más consumieron desde [fromDay], de más a menos. */
    fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTraffic>>

    /** Las apps que más consumieron desde [fromDay], de más a menos; solo donde se sabe la app (Android). */
    fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTraffic>>

    /** Borra lo anterior a [day]. */
    suspend fun deleteBefore(day: Int)
}

/** El día local de [clock] en [timeZone], contado desde el 1 de enero de 1970. */
fun localDay(clock: Clock, timeZone: TimeZone = TimeZone.currentSystemDefault()): Int =
    clock.now().toLocalDateTime(timeZone).date.toEpochDays().toInt()

/**
 * Guarda en [history] el tráfico que publica [monitor], sumado por día y por lotes.
 *
 * Como [org.uci.proxy.core.log.LogRecorder], solo lo usa el proceso de la interfaz: en escritorio el motor es otro
 * proceso y su tráfico llega a [monitor] por el canal del motor. Si guardar falla, lo pendiente se reintenta en la
 * siguiente vuelta.
 */
class TrafficRecorder(
    private val monitor: TrafficMonitor,
    private val history: TrafficHistory,
    private val clock: Clock,
    dispatchers: AppDispatchers,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val saveInterval: Duration = 10.seconds,
    private val retentionDays: Int = RETENTION_DAYS,
) {
    private data class Key(val exit: RouteExit, val host: String?, val app: String?)

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.background)
    private val lock = Mutex()

    /** Lo medido y aún no guardado, por día. */
    private val pending = LinkedHashMap<Int, LinkedHashMap<Key, LongArray>>()
    private var job: Job? = null

    /** Empieza a guardar. Llamarlo otra vez no hace nada. */
    fun start(): Job = job ?: scope.launch {
        launch { monitor.deltas.collect { add(it) } }
        attempt { history.deleteBefore(today() - retentionDays) }
        var lastPrune = clock.now()
        while (isActive) {
            delay(saveInterval)
            saveNow()
            if (clock.now() - lastPrune >= PRUNE_INTERVAL) {
                attempt { history.deleteBefore(today() - retentionDays) }
                lastPrune = clock.now()
            }
        }
    }.also { job = it }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Hoy, en la hora local: el día al que se suma lo que se mide ahora. */
    fun today(): Int = localDay(clock, timeZone())

    private suspend fun add(delta: TrafficDelta) = lock.withLock {
        val day = pending.getOrPut(today()) { LinkedHashMap() }
        for (usage in delta.usage) {
            val bytes = day.getOrPut(Key(usage.exit, usage.host, usage.app)) { LongArray(2) }
            bytes[0] += usage.up
            bytes[1] += usage.down
        }
    }

    /** Guarda ya lo pendiente, sin esperar a la siguiente vuelta. */
    suspend fun saveNow() = lock.withLock {
        val days = pending.entries.toList()
        for ((day, usage) in days) {
            attempt {
                history.add(day, usage.map { (key, bytes) -> TrafficUsage(key.exit, key.host, key.app, bytes[0], bytes[1]) })
                pending.remove(day)
            }
        }
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
        /** Lo que se guarda: unos tres meses, de sobra para el historial de la pantalla. */
        const val RETENTION_DAYS = 90
        private val PRUNE_INTERVAL = 6.hours
    }
}
