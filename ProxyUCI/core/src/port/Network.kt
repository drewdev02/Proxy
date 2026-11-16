package org.uci.proxy.core.port

/** Una conexión TCP abierta. Las implementaciones lanzan [TcpException] ante cualquier fallo de red. */
interface TcpConnection {
    /** Lee hasta [maxBytes]. Devuelve null cuando el otro extremo cerró. */
    suspend fun read(maxBytes: Int = DEFAULT_READ_SIZE): ByteArray?

    suspend fun write(bytes: ByteArray)

    fun close()

    companion object {
        const val DEFAULT_READ_SIZE = 8192
    }
}

fun interface TcpConnector {
    /** @throws TcpException si no se puede resolver el nombre o establecer la conexión. */
    suspend fun connect(host: String, port: Int): TcpConnection
}

class TcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Bytes aleatorios de calidad criptográfica, del generador del sistema. */
fun interface RandomBytes {
    fun next(size: Int): ByteArray
}
