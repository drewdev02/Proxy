package org.uci.proxy.core.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunnelState
import java.io.BufferedReader
import java.io.IOException
import java.io.PrintWriter
import java.net.Socket
import kotlin.time.Duration

/**
 * Lado del motor: atiende a la ventana por el socket ya conectado y ejecuta un túnel.
 * Termina cuando el túnel acaba, la ventana pide parar o cierra el socket, o se agota [maxDuration].
 *
 * @param maxDuration tope de vida del túnel; solo para pruebas, como salvaguarda.
 */
class EngineSession(
    private val runner: TunnelRunner,
    private val stateStore: TunnelStateStore,
    private val platformTunnel: PlatformTunnel,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
    /** Los mensajes de registro de este proceso: se reenvían a la ventana, que no puede ver los del motor. */
    private val logBuffer: LogBuffer,
    private val maxDuration: Duration? = null,
) {
    suspend fun serve(socket: Socket, token: String) = coroutineScope {
        val reader = socket.getInputStream().bufferedReader()
        val writer = PrintWriter(socket.getOutputStream().bufferedWriter(), true)

        writer.println(token)
        val start = withContext(ioDispatcher) { reader.readLineOrNull() }?.let(EngineProtocol::decodeStart)
        if (start == null) {
            logger.log(LogLevel.ERROR, TAG, "La ventana no envió una orden de arranque válida")
            return@coroutineScope
        }

        // Lo que el motor registró desde que arrancó (incluido lo anterior a esta orden) y, a partir de ahí, lo nuevo.
        var lastLogSent = -1L
        fun forwardNewLogs(entries: List<LogEntry>) {
            entries.filter { it.id > lastLogSent }.forEach { entry ->
                writer.println(EngineProtocol.encodeLog(entry))
                lastLogSent = entry.id
            }
        }
        val logForwarder = launch { logBuffer.entries.collect(::forwardNewLogs) }

        val tunnel = launch { runner.run(platformTunnel, start.profile, start.password, start.settings) }
        val reporter = launch {
            // El estado de partida es "desconectado": enviarlo haría creer a la ventana que el túnel ya terminó.
            stateStore.state
                .dropWhile { it == TunnelState.Disconnected }
                .collect { state -> EngineProtocol.encodeState(state)?.let(writer::println) }
        }
        // Cualquier cosa que llegue, o el cierre del socket, significa parar.
        val listener = launch(ioDispatcher) {
            reader.readLineOrNull()
            tunnel.cancel()
        }
        val watchdog = maxDuration?.let { limit ->
            launch {
                delay(limit)
                logger.log(LogLevel.WARN, TAG, "Tope de $limit alcanzado: se detiene el túnel")
                tunnel.cancel()
            }
        }

        tunnel.join()
        // Lo último que registró el túnel al acabar («Túnel detenido», un fallo…) se envía antes del estado final.
        logForwarder.cancelAndJoin()
        forwardNewLogs(logBuffer.entries.value)
        // El último estado ("desconectado" o el fallo) ya está publicado: se envía antes de cerrar.
        EngineProtocol.encodeState(stateStore.state.value)?.let(writer::println)
        reporter.cancel()
        watchdog?.cancel()
        // Cerrar el socket despierta al lector, que está bloqueado en una lectura.
        socket.close()
        listener.cancel()
    }

    private fun BufferedReader.readLineOrNull(): String? = try {
        readLine()
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val TAG = "Engine"
    }
}
