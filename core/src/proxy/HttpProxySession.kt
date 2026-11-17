package org.uci.proxy.core.proxy

import org.uci.proxy.core.net.BufferedTcpConnection
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.port.TcpException

/** Cabecera de una respuesta HTTP: línea de estado y cabeceras. */
class HttpResponseHead(val statusLine: String, val statusCode: Int, private val headers: List<Pair<String, String>>) {
    fun values(name: String): List<String> =
        headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val contentLength: Int?
        get() = values("Content-Length").firstOrNull()?.trim()?.toIntOrNull()

    val isChunked: Boolean
        get() = values("Transfer-Encoding").any { it.contains("chunked", ignoreCase = true) }

    val closesConnection: Boolean
        get() = (values("Connection") + values("Proxy-Connection")).any { it.trim().equals("close", ignoreCase = true) }
}

/**
 * Las peticiones `CONNECT` de un intento de túnel hacia [target].
 *
 * Reutiliza la conexión con el proxy mientras este la mantenga abierta y abre otra cuando no.
 * NTLM autentica la conexión, no la petición, así que sus pasos exigen seguir en la misma.
 */
class HttpProxySession(
    private val tcpConnector: TcpConnector,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val target: ProxyTarget,
) {
    private var connection: BufferedTcpConnection? = null

    /**
     * Envía un `CONNECT` con la cabecera `Proxy-Authorization` indicada (o sin ella) y lee la respuesta.
     *
     * @param sameConnection exige continuar en la conexión de la petición anterior; si el proxy la
     *   cerró, lanza [TcpException] en lugar de abrir una nueva.
     * @throws TcpException ante fallos de red o respuestas que no son HTTP.
     */
    suspend fun connect(authorization: String?, sameConnection: Boolean = false): HttpResponseHead {
        val current = connection
            ?: if (sameConnection) {
                throw TcpException("El proxy cerró la conexión a mitad de la autenticación")
            } else {
                BufferedTcpConnection(tcpConnector.connect(proxyHost, proxyPort)).also { connection = it }
            }

        current.write(buildRequest(authorization).encodeToByteArray())
        val head = readHead(current)

        // Con 2xx la conexión pasa a ser el túnel. En cualquier otro caso hay que dejarla limpia
        // para la siguiente petición, o descartarla si no se puede.
        if (head.statusCode !in 200..299 && (head.closesConnection || !drainBody(current, head))) {
            close()
        }
        return head
    }

    /** Entrega la conexión, ya convertida en túnel, tras una respuesta 2xx. */
    fun takeTunnel(): BufferedTcpConnection =
        checkNotNull(connection) { "No hay túnel abierto" }.also { connection = null }

    fun close() {
        connection?.close()
        connection = null
    }

    private fun buildRequest(authorization: String?): String = buildString {
        val authority = "${target.host}:${target.port}"
        append("CONNECT $authority HTTP/1.1\r\n")
        append("Host: $authority\r\n")
        append("Proxy-Connection: keep-alive\r\n")
        append("Connection: keep-alive\r\n")
        if (authorization != null) append("Proxy-Authorization: $authorization\r\n")
        append("User-Agent: $USER_AGENT\r\n")
        append("\r\n")
    }

    private suspend fun readHead(connection: BufferedTcpConnection): HttpResponseHead {
        val statusLine = connection.readLine() ?: throw TcpException("El proxy cerró la conexión sin responder")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (!statusLine.startsWith("HTTP/") || statusCode == null) {
            throw TcpException("La respuesta no es HTTP: ${statusLine.take(60)}")
        }

        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = connection.readLine() ?: throw TcpException("El proxy cortó la respuesta")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw TcpException("Demasiadas cabeceras en la respuesta del proxy")
            val colon = line.indexOf(':')
            if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return HttpResponseHead(statusLine, statusCode, headers)
    }

    /** Consume el cuerpo de una respuesta de error. Devuelve false si no se sabe cuánto mide. */
    private suspend fun drainBody(connection: BufferedTcpConnection, head: HttpResponseHead): Boolean {
        if (head.isChunked) {
            while (true) {
                val sizeLine = connection.readLine() ?: return false
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return false
                if (size > MAX_BODY) return false
                if (size == 0) break
                connection.readExactly(size)
                connection.readLine() // fin de línea tras cada trozo
            }
            // Cabeceras finales opcionales, hasta la línea vacía.
            while (true) {
                val trailer = connection.readLine() ?: return false
                if (trailer.isEmpty()) return true
            }
        }
        val length = head.contentLength ?: 0
        if (length > MAX_BODY) return false
        if (length > 0) connection.readExactly(length)
        return true
    }

    private companion object {
        const val USER_AGENT = "ProxyUCI"
        const val MAX_HEADERS = 100
        const val MAX_BODY = 1024 * 1024
    }
}
