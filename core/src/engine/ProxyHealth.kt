package org.uci.proxy.core.engine

import org.uci.proxy.core.port.ProxyProblem
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Si el proxy ha abierto alguna conexión hace poco. Sin eso no se puede asegurar que haya internet. */
class ProxyHealth(
    private val timeSource: TimeSource,
    private val window: Duration = 5.minutes,
) {
    // Lo escriben los relés de cada conexión, que pueden correr en hilos distintos.
    @Volatile
    private var lastSuccess: TimeMark? = null

    // Fallos seguidos desde el último éxito. Son orientativos: una carrera entre relés solo cambia la cuenta en uno.
    @Volatile
    private var authFailures = 0

    @Volatile
    private var unreachable = 0

    fun markSuccess() {
        lastSuccess = timeSource.markNow()
        authFailures = 0
        unreachable = 0
    }

    /** El proxy rechazó las credenciales del perfil. */
    fun markAuthRejected() {
        authFailures++
    }

    /** No se llegó al proxy, o no contestó a tiempo. Un rechazo de un destino concreto no cuenta: el proxy funciona. */
    fun markUnreachable() {
        unreachable++
    }

    fun isHealthy(): Boolean = lastSuccess?.let { it.elapsedNow() <= window } ?: false

    /** Lo que le pasa al proxy si varias conexiones seguidas fallaron igual; null si va bien o no se sabe. */
    val problem: ProxyProblem?
        get() = when {
            authFailures >= FAILURES_FOR_PROBLEM -> ProxyProblem.AUTH_REJECTED
            unreachable >= FAILURES_FOR_PROBLEM -> ProxyProblem.UNREACHABLE
            else -> null
        }

    private companion object {
        /** Una sola conexión fallida puede ser mala suerte; tres seguidas, sin ningún éxito en medio, no. */
        const val FAILURES_FOR_PROBLEM = 3
    }
}
