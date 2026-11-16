package org.uci.proxy.core.fakes

import kotlinx.coroutines.channels.Channel
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.port.TcpException
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget

/** Túnel ya abierto hacia un destino simulado: la prueba decide qué "responde" el servidor. */
class FakeTunnelConnection : TcpConnection {
    private val incoming = Channel<Result<ByteArray>>(Channel.UNLIMITED)
    val written = mutableListOf<ByteArray>()
    var outputShutDown = false
        private set
    var closed = false
        private set

    val writtenText: String get() = written.joinToString("") { it.decodeToString() }

    /** El servidor envía [text]. */
    fun deliver(text: String) {
        incoming.trySend(Result.success(text.encodeToByteArray()))
    }

    /** El servidor termina de enviar. */
    fun end() {
        incoming.close()
    }

    /** La conexión con el proxy se rompe. */
    fun fail() {
        incoming.trySend(Result.failure(TcpException("conexión con el proxy perdida")))
    }

    override suspend fun read(maxBytes: Int): ByteArray? = incoming.receiveCatching().getOrNull()?.getOrThrow()

    override suspend fun write(bytes: ByteArray) {
        written += bytes
    }

    override suspend fun shutdownOutput() {
        outputShutDown = true
    }

    override fun close() {
        closed = true
    }
}

/** Conector que anota a qué destinos se le pide conectar y responde lo que diga [answer]. */
class FakeProxyConnector(
    override val protocol: ProxyProtocol = ProxyProtocol.HTTP,
    private val answer: suspend (ProxyTarget) -> ProxyConnectResult,
) : ProxyConnector {
    val targets = mutableListOf<ProxyTarget>()
    val passwords = mutableListOf<String>()

    override suspend fun connect(profile: ProxyProfile, password: String, target: ProxyTarget): ProxyConnectResult {
        targets += target
        passwords += password
        return answer(target)
    }

    companion object {
        fun connectingTo(connection: FakeTunnelConnection) =
            FakeProxyConnector { ProxyConnectResult.Connected(connection) }

        fun failingWith(failure: ProxyFailure) = FakeProxyConnector { ProxyConnectResult.Failed(failure) }
    }
}
