package org.uci.proxy.core.proxy

import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeConnection
import org.uci.proxy.core.fakes.FakeTcpConnector
import org.uci.proxy.core.fakes.unreachableConnector
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Socks5ConnectorTest {
    private val target = ProxyTarget("example.org", 443)

    private fun profile(username: String = "") =
        ProxyProfile("id", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, username)

    /** Servidor SOCKS5 simulado. Tras aceptar el túnel hace eco. */
    private class FakeSocksServer(
        private val requireAuth: Boolean = false,
        private val username: String = "andrey",
        private val password: String = "secreta",
        private val replyCode: Byte = 0,
    ) {
        var requestedDestination: ByteArray? = null
        private var step = 0

        val connector = FakeTcpConnector { FakeConnection(::handle) }

        private fun handle(bytes: ByteArray): ByteArray? = when (step++) {
            0 -> {
                val offered = bytes.drop(2)
                when {
                    requireAuth && 2 in offered.map { it.toInt() } -> byteArrayOf(5, 2)
                    requireAuth -> byteArrayOf(5, 0xFF.toByte()).also { step = 99 }
                    else -> byteArrayOf(5, 0).also { step = 2 }
                }
            }
            1 -> {
                val userLength = bytes[1].toInt()
                val user = bytes.decodeToString(2, 2 + userLength)
                val pass = bytes.decodeToString(3 + userLength, bytes.size)
                byteArrayOf(1, if (user == username && pass == password) 0 else 1)
            }
            2 -> {
                requestedDestination = bytes
                // Respuesta con dirección IPv4 0.0.0.0 y puerto 0.
                byteArrayOf(5, replyCode, 0, 1, 0, 0, 0, 0, 0, 0)
            }
            else -> bytes
        }
    }

    private suspend fun connect(server: FakeSocksServer, username: String = "", password: String = "") =
        Socks5Connector(server.connector).connect(profile(username), password, target)

    private fun ProxyConnectResult.failure(): ProxyFailure = assertIs<ProxyConnectResult.Failed>(this).failure

    @Test
    fun `a server without authentication gives a tunnel to the named destination`() = runTest {
        val server = FakeSocksServer()

        val result = assertIs<ProxyConnectResult.Connected>(connect(server))

        // VER=5 CMD=CONNECT RSV ATYP=dominio LEN "example.org" puerto 443 (0x01BB)
        val expected = byteArrayOf(5, 1, 0, 3, 11) + "example.org".encodeToByteArray() + byteArrayOf(1, 0xBB.toByte())
        assertContentEquals(expected, server.requestedDestination)
        result.connection.write("hola".encodeToByteArray())
        assertEquals("hola", result.connection.read()?.decodeToString())
    }

    @Test
    fun `user and password open the tunnel`() = runTest {
        val server = FakeSocksServer(requireAuth = true)

        assertIs<ProxyConnectResult.Connected>(connect(server, "andrey", "secreta"))
    }

    @Test
    fun `a wrong password is reported as rejected`() = runTest {
        val server = FakeSocksServer(requireAuth = true)

        assertEquals(ProxyFailure.AuthRejected, connect(server, "andrey", "otra").failure())
    }

    @Test
    fun `a server that wants credentials when the profile has none`() = runTest {
        val server = FakeSocksServer(requireAuth = true)

        assertEquals(ProxyFailure.AuthRequired, connect(server).failure())
    }

    @Test
    fun `a refused destination is reported with the SOCKS code`() = runTest {
        val server = FakeSocksServer(replyCode = 5)

        assertEquals(ProxyFailure.Refused("SOCKS5 código 5"), connect(server).failure())
    }

    @Test
    fun `a server that is not SOCKS5 is a protocol error`() = runTest {
        val tcp = FakeTcpConnector { FakeConnection { "HTTP/1.1 400 Bad Request\r\n\r\n".encodeToByteArray() } }

        val result = Socks5Connector(tcp).connect(profile(), "", target)

        assertIs<ProxyFailure.ProtocolError>(result.failure())
    }

    @Test
    fun `a server that hangs up mid handshake is unreachable`() = runTest {
        val tcp = FakeTcpConnector { FakeConnection { null } }

        val result = Socks5Connector(tcp).connect(profile(), "", target)

        assertIs<ProxyFailure.Unreachable>(result.failure())
    }

    @Test
    fun `a failed connection is unreachable`() = runTest {
        val result = Socks5Connector(unreachableConnector).connect(profile(), "", target)

        assertEquals(ProxyFailure.Unreachable("no se pudo conectar con 10.0.0.1"), result.failure())
    }
}
