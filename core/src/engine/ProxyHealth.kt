package org.uci.proxy.core.engine

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

    fun markSuccess() {
        lastSuccess = timeSource.markNow()
    }

    fun isHealthy(): Boolean = lastSuccess?.let { it.elapsedNow() <= window } ?: false
}
