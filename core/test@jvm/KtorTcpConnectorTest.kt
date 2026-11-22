package org.uci.proxy.core

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.port.TcpException
import org.uci.proxy.core.common.AppDispatchers
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Sockets reales contra un servidor local en un puerto libre. */
class KtorTcpConnectorTest {
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val connector = KtorTcpConnector(selectorManager, AppDispatchers(background = Dispatchers.IO))
    private val server = ServerSocket(0)

    @AfterTest
    fun cleanUp() {
        server.close()
        selectorManager.close()
    }

    /** Atiende una conexión: responde en mayúsculas a lo primero que recibe y cierra. */
    private fun serveOnce() = thread {
        server.accept().use { client ->
            val buffer = ByteArray(1024)
            val count = client.getInputStream().read(buffer)
            client.getOutputStream().write(String(buffer, 0, count).uppercase().toByteArray())
        }
    }

    @Test
    fun `writes reach the server and its reply is read back`() = runBlocking {
        serveOnce()
        val connection = connector.connect("127.0.0.1", server.localPort)

        connection.write("hola proxy".encodeToByteArray())

        assertEquals("HOLA PROXY", connection.read()?.decodeToString())
        connection.close()
    }

    @Test
    fun `read returns null once the server closes`() = runBlocking {
        serveOnce()
        val connection = connector.connect("127.0.0.1", server.localPort)
        connection.write("x".encodeToByteArray())
        connection.read()

        assertNull(connection.read())
        connection.close()
    }

    @Test
    fun `a closed port is reported as TcpException`() {
        val freePort = ServerSocket(0).use { it.localPort }

        assertFailsWith<TcpException> { runBlocking { connector.connect("127.0.0.1", freePort) } }
    }

    @Test
    fun `an unknown host is reported as TcpException`() {
        assertFailsWith<TcpException> { runBlocking { connector.connect("no-existe.invalid", 80) } }
    }
}
