package org.uci.proxy.core.fakes

import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.port.TcpException

/**
 * Conexión contra un servidor simulado: cada `write` del cliente se entrega a [onWrite], que devuelve
 * los bytes con que responde el servidor, o null para cerrar la conexión.
 */
class FakeConnection(private val onWrite: (ByteArray) -> ByteArray?) : TcpConnection {
    private val pending = ArrayDeque<ByteArray>()
    val written = mutableListOf<ByteArray>()
    var closedByClient = false
        private set
    private var closedByServer = false

    override suspend fun read(maxBytes: Int): ByteArray? {
        val next = pending.removeFirstOrNull() ?: return null
        if (next.size <= maxBytes) return next
        pending.addFirst(next.copyOfRange(maxBytes, next.size))
        return next.copyOf(maxBytes)
    }

    override suspend fun write(bytes: ByteArray) {
        if (closedByServer) throw TcpException("conexión cerrada por el servidor")
        written += bytes
        val reply = onWrite(bytes)
        if (reply == null) closedByServer = true else if (reply.isNotEmpty()) pending += reply
    }

    var outputShutDown = false
        private set

    override suspend fun shutdownOutput() {
        outputShutDown = true
    }

    override fun close() {
        closedByClient = true
    }
}

/** Entrega una [FakeConnection] nueva por cada conexión y recuerda todas las que abrió. */
class FakeTcpConnector(private val newConnection: (index: Int) -> FakeConnection) : TcpConnector {
    val connections = mutableListOf<FakeConnection>()
    val destinations = mutableListOf<Pair<String, Int>>()

    override suspend fun connect(host: String, port: Int): TcpConnection {
        destinations += host to port
        return newConnection(connections.size).also(connections::add)
    }
}

/** Un conector que nunca logra conectar. */
val unreachableConnector = TcpConnector { host, _ -> throw TcpException("no se pudo conectar con $host") }
