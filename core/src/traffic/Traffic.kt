package org.uci.proxy.core.traffic

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.uci.proxy.core.model.RouteExit

/*
 * Cuánto tráfico pasa por el túnel. Se cuenta en el motor lo que de verdad lleva: lo que va por el proxy (los bytes que
 * se copian en cada conexión) y lo que va por Otro País (los paquetes que entran y salen de la VPN). Lo que sale directo
 * no entra en la interfaz y no se ve; lo bloqueado no lleva datos.
 */

/**
 * Bytes de una salida hacia un destino en un intervalo.
 *
 * @param host el nombre (o la IP) del destino; null cuando no se sabe (todo por Otro País, sin reglas).
 * @param app la app que abrió la conexión, donde el sistema lo dice (Android, y solo para lo que va por el proxy).
 * @param up de las apps hacia fuera; [down] al revés.
 */
@Serializable
data class TrafficUsage(val exit: RouteExit, val host: String?, val app: String?, val up: Long, val down: Long)

/**
 * Lo que pasó por el túnel en un intervalo (normalmente un segundo): el total, las conexiones abiertas al final y el
 * reparto por salida, destino y app.
 */
@Serializable
data class TrafficDelta(
    val millis: Long,
    val up: Long,
    val down: Long,
    val connections: Int,
    val usage: List<TrafficUsage> = emptyList(),
)

/**
 * Cuenta los bytes de una sesión del túnel. Lo usan a la vez los relés de cada conexión, el bucle del motor y el de la
 * VPN; [drain] se llama cada segundo para llevarse lo acumulado.
 */
class TrafficMeter {
    private data class Key(val exit: RouteExit, val host: String?, val app: String?)

    private val lock = Mutex()
    private val pending = LinkedHashMap<Key, LongArray>()
    private var connections = 0

    suspend fun count(exit: RouteExit, host: String?, app: String?, up: Long, down: Long) {
        if (up == 0L && down == 0L) return
        lock.withLock {
            val bytes = pending.getOrPut(Key(exit, host?.lowercase(), app)) { LongArray(2) }
            bytes[0] += up
            bytes[1] += down
        }
    }

    suspend fun opened() = lock.withLock { connections++ }

    suspend fun closed() = lock.withLock { connections = maxOf(0, connections - 1) }

    /** Lo acumulado desde la última vez, que se da por entregado. @param millis el tiempo que cubre. */
    suspend fun drain(millis: Long): TrafficDelta = lock.withLock {
        val usage = pending.map { (key, bytes) -> TrafficUsage(key.exit, key.host, key.app, bytes[0], bytes[1]) }
        pending.clear()
        TrafficDelta(millis, usage.sumOf { it.up }, usage.sumOf { it.down }, connections, usage)
    }
}

/** La sesión en curso vista desde la interfaz. Las velocidades van en bytes por segundo, de la más antigua a la última. */
data class LiveTraffic(
    val upRates: List<Long> = emptyList(),
    val downRates: List<Long> = emptyList(),
    val sessionUp: Long = 0,
    val sessionDown: Long = 0,
    val connections: Int = 0,
) {
    val upRate: Long get() = upRates.lastOrNull() ?: 0
    val downRate: Long get() = downRates.lastOrNull() ?: 0
    val sessionTotal: Long get() = sessionUp + sessionDown

    /** Si ya llegó alguna medida: antes no hay nada que mostrar (ni ceros inventados). */
    val measured: Boolean get() = downRates.isNotEmpty()
}

/** Lo que la interfaz usa para ver el tráfico. */
interface TrafficMonitor {
    /** La sesión en curso: velocidades del último minuto, totales y conexiones. */
    val live: StateFlow<LiveTraffic>

    /** Cada medida tal cual llega, para guardarla (ver [TrafficRecorder]). */
    val deltas: SharedFlow<TrafficDelta>
}

/**
 * El tráfico, compartido entre quien lo mide y quien lo mira dentro del mismo proceso. En escritorio el motor corre en
 * otro proceso: allí tiene el suyo, y la ventana recibe cada medida por el canal del motor y la anota en el de la ventana.
 */
class TrafficStore(private val history: Int = HISTORY_SECONDS) : TrafficMonitor {
    private val mutableLive = MutableStateFlow(LiveTraffic())
    override val live: StateFlow<LiveTraffic> = mutableLive.asStateFlow()

    // Sin suscriptor (el motor de escritorio, sin pantalla) las medidas se pierden; con él, no se espera a nadie.
    private val mutableDeltas = MutableSharedFlow<TrafficDelta>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deltas: SharedFlow<TrafficDelta> = mutableDeltas.asSharedFlow()

    /** Empieza una sesión: las velocidades y los totales vuelven a cero. */
    fun reset() {
        mutableLive.value = LiveTraffic()
    }

    fun record(delta: TrafficDelta) {
        val millis = delta.millis.coerceAtLeast(1)
        mutableLive.value = mutableLive.value.let { now ->
            LiveTraffic(
                upRates = (now.upRates + delta.up * 1000 / millis).takeLast(history),
                downRates = (now.downRates + delta.down * 1000 / millis).takeLast(history),
                sessionUp = now.sessionUp + delta.up,
                sessionDown = now.sessionDown + delta.down,
                connections = delta.connections,
            )
        }
        if (delta.up > 0 || delta.down > 0) mutableDeltas.tryEmit(delta)
    }

    private companion object {
        const val HISTORY_SECONDS = 60
    }
}
