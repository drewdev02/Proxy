package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.ProtocolIssue
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeConnection
import org.uci.proxy.core.fakes.FakeHttpProxy
import org.uci.proxy.core.fakes.FakeTcpConnector
import org.uci.proxy.core.fakes.unreachableConnector
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TcpConnector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import org.uci.proxy.core.port.NetFailure

class HttpConnectConnectorTest {
    private val target = ProxyTarget("example.org", 443)

    private fun profile(username: String = "andrey") =
        ProxyProfile("id", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, username)

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    private fun connector(tcp: TcpConnector) = HttpConnectConnector(
        tcpConnector = tcp,
        schemes = listOf(NtlmAuthScheme(randomBytes = { ByteArray(it) { 0x55 } }, clock = fixedClock), BasicAuthScheme()),
    )

    private suspend fun connect(proxy: FakeHttpProxy, username: String = "andrey", password: String = "secreta") =
        connector(proxy.connector).connect(profile(username), password, target)

    private fun ProxyConnectResult.failure(): ProxyFailure = assertIs<ProxyConnectResult.Failed>(this).failure

    @Test
    fun `an open proxy gives a tunnel`() = runTest {
        val proxy = FakeHttpProxy()

        val result = assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertEquals(listOf("CONNECT example.org:443 HTTP/1.1"), proxy.requestLines)
        assertEquals(listOf("proxy.uci.cu" to 3128), proxy.connector.destinations)
        result.connection.write("hola".encodeToByteArray())
        assertEquals("hola", result.connection.read()?.decodeToString())
    }

    @Test
    fun `data that arrives glued to the 200 response belongs to the tunnel`() = runTest {
        val proxy = FakeHttpProxy(earlyTunnelData = "saludo del destino")

        val result = assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertEquals("saludo del destino", result.connection.read()?.decodeToString())
    }

    @Test
    fun `Basic credentials open the tunnel`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"))

        assertIs<ProxyConnectResult.Connected>(connect(proxy))
    }

    @Test
    fun `the first request never carries credentials`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"))

        connect(proxy)

        assertNull(proxy.authorizations.first())
    }

    @Test
    fun `a wrong Basic password is reported as rejected`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"))

        assertEquals(ProxyFailure.AuthRejected, connect(proxy, password = "otra").failure())
    }

    @Test
    fun `a proxy that wants credentials when the profile has none`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"))

        assertEquals(ProxyFailure.AuthRequired, connect(proxy, username = "", password = "").failure())
    }

    @Test
    fun `NTLM credentials open the tunnel over a single connection`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("NTLM"))

        assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertEquals(1, proxy.connector.connections.size)
        assertEquals(3, proxy.authorizations.size)
    }

    @Test
    fun `NTLM accepts the DOMAIN backslash user form`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("NTLM"))

        assertIs<ProxyConnectResult.Connected>(connect(proxy, username = "UCI\\andrey"))
    }

    @Test
    fun `a wrong NTLM password is reported as rejected`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("NTLM"))

        assertEquals(ProxyFailure.AuthRejected, connect(proxy, password = "otra").failure())
    }

    @Test
    fun `NTLM is preferred and the password is never sent as Basic`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic", "NTLM"))

        assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertTrue(proxy.authorizations.filterNotNull().all { it.startsWith("NTLM ") })
    }

    @Test
    fun `only unknown schemes are reported as unsupported`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Negotiate", "Digest realm=\"x\""))

        assertEquals(ProxyFailure.UnsupportedAuth(listOf("Negotiate", "Digest")), connect(proxy).failure())
    }

    @Test
    fun `Basic still works when the proxy closes the connection after each 407`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"), closeAfter407 = true)

        assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertEquals(2, proxy.connector.connections.size)
    }

    @Test
    fun `NTLM fails cleanly when the proxy closes the connection after the challenge`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("NTLM"), closeAfter407 = true)

        assertIs<ProxyFailure.Unreachable>(connect(proxy).failure())
    }

    @Test
    fun `a chunked 407 body is drained so the connection can be reused`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("NTLM"), chunked407 = true)

        assertIs<ProxyConnectResult.Connected>(connect(proxy))

        assertEquals(1, proxy.connector.connections.size)
    }

    @Test
    fun `a refusal for the destination is reported with the status line`() = runTest {
        val proxy = FakeHttpProxy(statusWhenAuthorized = "403 Forbidden")

        assertEquals(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"), connect(proxy).failure())
    }

    @Test
    fun `a failed connection to the proxy is unreachable`() = runTest {
        val result = connector(unreachableConnector).connect(profile(), "secreta", target)

        assertEquals(ProxyFailure.Unreachable(NetFailure.REFUSED, "no se pudo conectar con proxy.uci.cu"), result.failure())
    }

    @Test
    fun `a server that does not speak HTTP is a protocol error and not an unreachable proxy`() = runTest {
        val tcp = FakeTcpConnector { FakeConnection { "SSH-2.0-OpenSSH\r\n".encodeToByteArray() } }

        val failure = assertIs<ProxyFailure.ProtocolError>(connector(tcp).connect(profile(), "secreta", target).failure())
        assertEquals(ProtocolIssue.NOT_HTTP_PROXY, failure.issue)
    }

    @Test
    fun `the connection is closed after a failure`() = runTest {
        val proxy = FakeHttpProxy(offered = listOf("Basic"))

        connect(proxy, password = "otra")

        assertTrue(proxy.connector.connections.all { it.closedByClient })
    }
}
