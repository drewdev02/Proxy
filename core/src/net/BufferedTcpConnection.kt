package org.uci.proxy.core.net

import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.port.TcpException

/**
 * Añade lectura por líneas y por cantidad exacta sobre una [TcpConnection].
 *
 * Lo que se haya leído de más queda guardado y lo entrega el siguiente [read]: es imprescindible
 * tras un `CONNECT`, porque los bytes que lleguen pegados a la respuesta del proxy ya son del túnel.
 */
class BufferedTcpConnection(private val delegate: TcpConnection) : TcpConnection {
    private var buffer = ByteArray(0)

    override suspend fun read(maxBytes: Int): ByteArray? {
        if (buffer.isEmpty()) return delegate.read(maxBytes)
        val chunk = buffer.copyOf(minOf(maxBytes, buffer.size))
        buffer = buffer.copyOfRange(chunk.size, buffer.size)
        return chunk
    }

    override suspend fun write(bytes: ByteArray) = delegate.write(bytes)

    override suspend fun shutdownOutput() = delegate.shutdownOutput()

    override fun close() = delegate.close()

    /**
     * Lee una línea terminada en LF y la devuelve sin el fin de línea (CRLF o LF).
     * Devuelve null si la conexión se cierra antes de recibir nada.
     *
     * @throws TcpException si la línea supera [maxLength] o la conexión se corta a mitad de línea.
     */
    suspend fun readLine(maxLength: Int = MAX_LINE_LENGTH): String? {
        while (true) {
            val newline = buffer.indexOf(LF)
            if (newline >= 0) {
                val end = if (newline > 0 && buffer[newline - 1] == CR) newline - 1 else newline
                val line = buffer.decodeToString(0, end)
                buffer = buffer.copyOfRange(newline + 1, buffer.size)
                return line
            }
            if (buffer.size > maxLength) throw TcpException(NetFailure.PROTOCOL, "Línea demasiado larga del servidor")
            val more = delegate.read()
            if (more == null) {
                if (buffer.isEmpty()) return null
                throw TcpException(NetFailure.CLOSED, "La conexión se cerró a mitad de una línea")
            }
            buffer += more
        }
    }

    /** @throws TcpException si la conexión se cierra antes de reunir [count] bytes. */
    suspend fun readExactly(count: Int): ByteArray {
        while (buffer.size < count) {
            buffer += delegate.read() ?: throw TcpException(NetFailure.CLOSED, "La conexión se cerró antes de recibir $count bytes")
        }
        val out = buffer.copyOf(count)
        buffer = buffer.copyOfRange(count, buffer.size)
        return out
    }

    private companion object {
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        const val MAX_LINE_LENGTH = 16 * 1024
    }
}
