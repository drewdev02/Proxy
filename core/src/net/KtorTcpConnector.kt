package org.uci.proxy.core.net

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.port.TcpException
import org.uci.proxy.core.profile.AppDispatchers

/** Sockets TCP reales mediante ktor-network, igual en JVM, Android e iOS. */
class KtorTcpConnector(
    private val selectorManager: SelectorManager,
    private val dispatchers: AppDispatchers,
) : TcpConnector {
    // Resolver el nombre y conectar son llamadas bloqueantes: fuera del hilo de quien llama.
    // En Android, hacerlo en el hilo principal lanza NetworkOnMainThreadException.
    override suspend fun connect(host: String, port: Int): TcpConnection = withContext(dispatchers.background) {
        guarded("No se pudo conectar con $host:$port") {
            KtorTcpConnection(aSocket(selectorManager).tcp().connect(host, port))
        }
    }
}

/** Una conexión ya abierta (por ejemplo, aceptada por un servidor local) con la misma interfaz que las salientes. */
fun Socket.asTcpConnection(): TcpConnection = KtorTcpConnection(this)

private class KtorTcpConnection(private val socket: Socket) : TcpConnection {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = false)

    override suspend fun read(maxBytes: Int): ByteArray? = guarded("Error al leer de la conexión") {
        val buffer = ByteArray(maxBytes)
        val count = input.readAvailable(buffer)
        if (count < 0) null else buffer.copyOf(count)
    }

    override suspend fun write(bytes: ByteArray) = guarded("Error al escribir en la conexión") {
        output.writeFully(bytes)
        output.flush()
    }

    override suspend fun shutdownOutput() = guarded("Error al cerrar el envío") {
        // Cerrar el canal de escritura hace que el socket envíe su FIN sin cerrar la lectura.
        output.flushAndClose()
    }

    override fun close() {
        socket.close()
    }
}

/**
 * Frontera con la librería de red: cada plataforma lanza tipos distintos ante un fallo
 * (nombre sin resolver, conexión rechazada, corte), así que aquí se unifican en [TcpException].
 */
private inline fun <T> guarded(message: String, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw TcpException("$message: ${e.message ?: e::class.simpleName}", e)
}
