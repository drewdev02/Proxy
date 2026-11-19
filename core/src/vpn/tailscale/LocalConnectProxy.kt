package org.uci.proxy.core.vpn.tailscale

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.net.asTcpConnection
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.vpn.ProxyDialResult
import org.uci.proxy.core.vpn.ProxyDialer
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Un proxy HTTP en 127.0.0.1 solo para la librería de Tailscale: lo que ella abre (servidor de control, relés DERP) sale
 * por el [ProxyDialer] del perfil, con su NTLM, sus nombres sslip.io y su forma de distinguir rechazos. Así Tailscale no
 * necesita saber nada del proxy institucional.
 *
 * Solo acepta peticiones con el usuario `ts` y un [token] aleatorio de un solo uso: otro programa del equipo no puede usarlo
 * para salir por el proxy del perfil. Atiende `CONNECT host:puerto` y, para un servidor de control `http://`, peticiones
 * normales con URL absoluta (que lleva tal cual al destino).
 */
class LocalConnectProxy(
    private val selector: SelectorManager,
    private val dial: ProxyDialer.Session,
    private val logger: Logger,
    random: RandomBytes,
    context: CoroutineContext,
) {
    val token: String = random.next(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private val expectedAuth = "Basic " + Base64.encode("$USER:$token".encodeToByteArray())
    private val scope = CoroutineScope(context + SupervisorJob())
    private var server: ServerSocket? = null

    /** El rechazo más informativo que dio el proxy del perfil, para explicar un fallo de Tailscale. */
    var worstFailure: ProxyDialResult.Reason? = null
        private set

    /** Cuántas veces rechazó el proxy del perfil sus credenciales: no se arregla reintentando. */
    var authFailures = 0
        private set

    /** Cuántas conexiones salieron bien por el proxy del perfil. */
    var connected = 0
        private set

    /** La URL que se le da a la librería. */
    val url: String get() = "http://$USER:$token@127.0.0.1:$port"

    var port: Int = 0
        private set

    /** Empieza a escuchar en un puerto libre de 127.0.0.1. */
    suspend fun start() {
        val socket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        server = socket
        port = (socket.localAddress as InetSocketAddress).port
        scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                launch { serve(client.asTcpConnection()) }
            }
        }
    }

    fun close() {
        server?.close()
        scope.cancel()
    }

    private suspend fun serve(client: TcpConnection) {
        try {
            val request = withTimeoutOrNull(HEADER_TIMEOUT) { readHeader(client) } ?: return
            val (head, rest) = request
            val lines = head.decodeToString().split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) return reply(client, "400 Bad Request")
            val auth = lines.drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            if (auth != expectedAuth) return reply(client, "407 Proxy Authentication Required")

            val (method, target) = parts
            val (host, port) = if (method == "CONNECT") {
                hostPort(target, defaultPort = 443) ?: return reply(client, "400 Bad Request")
            } else {
                // http://host[:puerto]/ruta: la petición entera (sin la autorización) se lleva al destino.
                val authority = target.removePrefix("http://").substringBefore('/')
                if (!target.startsWith("http://")) return reply(client, "400 Bad Request")
                hostPort(authority, defaultPort = 80) ?: return reply(client, "400 Bad Request")
            }

            val upstream = when (val result = dial.dial(host, port)) {
                is ProxyDialResult.Connected -> result.connection
                is ProxyDialResult.Failed -> {
                    note(result.reason)
                    logger.log(LogLevel.WARN, TAG, "El proxy no abrió $host:$port para Tailscale: ${result.detail}")
                    return reply(client, if (result.reason == ProxyDialResult.Reason.UNREACHABLE) "502 Bad Gateway" else "403 Forbidden")
                }
            }
            connected++
            try {
                if (method == "CONNECT") {
                    client.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
                } else {
                    val forwarded = lines.filterNot { it.startsWith("Proxy-Authorization:", ignoreCase = true) }.joinToString("\r\n")
                    upstream.write(forwarded.encodeToByteArray())
                }
                if (rest.isNotEmpty()) upstream.write(rest)
                pipe(client, upstream)
            } finally {
                upstream.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogLevel.DEBUG, TAG, "Conexión local de Tailscale terminada: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun note(reason: ProxyDialResult.Reason) {
        if (reason == ProxyDialResult.Reason.PROXY_AUTH) authFailures++
        val rank = listOf(ProxyDialResult.Reason.UNREACHABLE, ProxyDialResult.Reason.REFUSED, ProxyDialResult.Reason.PROXY_AUTH)
        val current = worstFailure
        if (current == null || rank.indexOf(reason) > rank.indexOf(current)) worstFailure = reason
    }

    /** Lee hasta el final de la cabecera; devuelve la cabecera (con su línea en blanco) y lo que llegó detrás. */
    private suspend fun readHeader(client: TcpConnection): Pair<ByteArray, ByteArray>? {
        var buffer = ByteArray(0)
        while (buffer.size < MAX_HEADER) {
            val chunk = client.read() ?: return null
            buffer += chunk
            val end = indexOfHeaderEnd(buffer)
            if (end >= 0) return buffer.copyOfRange(0, end + 4) to buffer.copyOfRange(end + 4, buffer.size)
        }
        return null
    }

    private fun indexOfHeaderEnd(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i
        }
        return -1
    }

    private fun hostPort(text: String, defaultPort: Int): Pair<String, Int>? {
        val host: String
        val port: Int
        if (text.startsWith("[")) return null // IPv6: la librería solo usa IPv4 y nombres
        val colon = text.lastIndexOf(':')
        if (colon >= 0) {
            host = text.substring(0, colon)
            port = text.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = text
            port = defaultPort
        }
        return if (host.isEmpty() || port !in 1..65535) null else host to port
    }

    private suspend fun reply(client: TcpConnection, status: String) {
        val extra = if (status.startsWith("407")) "Proxy-Authenticate: Basic realm=\"tsbridge\"\r\n" else ""
        client.write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
    }

    private suspend fun pipe(a: TcpConnection, b: TcpConnection) = coroutineScope {
        val ab = launch { copy(a, b) }
        val ba = launch { copy(b, a) }
        ab.join()
        ba.join()
    }

    private suspend fun copy(from: TcpConnection, to: TcpConnection) {
        try {
            while (true) {
                val chunk = from.read() ?: break
                to.write(chunk)
            }
            to.shutdownOutput()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            from.close()
            to.close()
        }
    }

    private companion object {
        const val TAG = "Tailscale"
        const val USER = "ts"
        const val MAX_HEADER = 16 * 1024
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
        val HEADER_TIMEOUT = 15.seconds
    }
}
