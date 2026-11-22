package org.uci.proxy.core.net

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.uci.proxy.core.dns.dnsTransactionId
import org.uci.proxy.core.port.DnsTransport
import org.uci.proxy.core.common.AppDispatchers

/** Consultas DNS por UDP con ktor-network, igual en JVM, Android e iOS. */
class KtorDnsTransport(
    private val selectorManager: SelectorManager,
    private val dispatchers: AppDispatchers,
    private val port: Int = DNS_PORT,
) : DnsTransport {
    override suspend fun exchange(server: String, query: ByteArray, timeoutMs: Long): ByteArray? = withContext(dispatchers.background) {
        try {
            aSocket(selectorManager).udp().connect(InetSocketAddress(server, port)).use { socket ->
                socket.send(Datagram(Buffer().apply { write(query) }, socket.remoteAddress))
                val id = dnsTransactionId(query)
                withTimeoutOrNull(timeoutMs) {
                    // Lo que no sea la respuesta a esta consulta (otra tardía, basura) se ignora.
                    var reply: ByteArray
                    do {
                        reply = socket.receive().packet.readByteArray()
                    } while (dnsTransactionId(reply) != id)
                    reply
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin red, servidor inalcanzable, puerto cerrado: para quien pregunta es lo mismo que no contestar.
            null
        }
    }

    private companion object {
        const val DNS_PORT = 53
    }
}
