package org.uci.proxy.core.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeConnection
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectingProxyTesterTest {
    private val target = ProxyTarget("example.org", 443)
    private val httpProfile = ProxyProfile("id", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

    private class StubConnector(
        override val protocol: ProxyProtocol,
        private val outcome: suspend () -> ProxyConnectResult,
    ) : ProxyConnector {
        var receivedTarget: ProxyTarget? = null
        var receivedPassword: String? = null

        override suspend fun connect(profile: ProxyProfile, password: String, target: ProxyTarget): ProxyConnectResult {
            receivedTarget = target
            receivedPassword = password
            return outcome()
        }
    }

    private fun tester(vararg connectors: ProxyConnector) = ConnectingProxyTester(connectors.toList(), target, 15.seconds)

    @Test
    fun `a tunnel that opens is a success and is closed right away`() = runTest {
        val connection = FakeConnection { it }
        val http = StubConnector(ProxyProtocol.HTTP) { ProxyConnectResult.Connected(connection) }

        val result = tester(http).test(httpProfile, "secreta")

        assertEquals(ProxyTestResult.Success, result)
        assertTrue(connection.closedByClient)
        assertEquals(target, http.receivedTarget)
        assertEquals("secreta", http.receivedPassword)
    }

    @Test
    fun `the connector for the profile protocol is the one used`() = runTest {
        val http = StubConnector(ProxyProtocol.HTTP) { ProxyConnectResult.Failed(ProxyFailure.AuthRejected) }
        val socks = StubConnector(ProxyProtocol.SOCKS5) { ProxyConnectResult.Connected(FakeConnection { it }) }

        val result = tester(http, socks).test(httpProfile.copy(protocol = ProxyProtocol.SOCKS5), "")

        assertEquals(ProxyTestResult.Success, result)
        assertEquals(null, http.receivedTarget)
    }

    @Test
    fun `a failure is passed through`() = runTest {
        val http = StubConnector(ProxyProtocol.HTTP) { ProxyConnectResult.Failed(ProxyFailure.AuthRejected) }

        assertEquals(ProxyTestResult.Failure(ProxyFailure.AuthRejected), tester(http).test(httpProfile, "otra"))
    }

    @Test
    fun `a proxy that never answers times out`() = runTest {
        val http = StubConnector(ProxyProtocol.HTTP) {
            delay(60.seconds)
            ProxyConnectResult.Failed(ProxyFailure.AuthRejected)
        }

        assertEquals(ProxyTestResult.Timeout, tester(http).test(httpProfile, "secreta"))
    }

    @Test
    fun `a protocol without connector is a protocol error`() = runTest {
        val socks = StubConnector(ProxyProtocol.SOCKS5) { ProxyConnectResult.Connected(FakeConnection { it }) }

        val result = tester(socks).test(httpProfile, "secreta")

        assertEquals(ProxyTestResult.Failure(ProxyFailure.ProtocolError("Protocolo HTTP no disponible")), result)
    }
}
