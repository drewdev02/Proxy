package org.uci.proxy.core.proxy

import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.IpNameService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.uci.proxy.core.port.NetFailure

class NameServiceTesterTest {
    private val profile = ProxyProfile("p", "UCI", "10.0.0.1", 3128, ProxyProtocol.HTTP, "andrey")

    private fun tester(connector: FakeProxyConnector) = ConnectingNameServiceTester(listOf(connector))

    @Test
    fun `a proxy that opens the tunnel by the service name means the service works`() = runTest {
        val tunnel = FakeTunnelConnection()
        val connector = FakeProxyConnector.connectingTo(tunnel)

        val result = tester(connector).test(IpNameService.SSLIP, profile, "clave")

        assertEquals(NameServiceTestResult.Works, result)
        assertEquals(listOf(ProxyTarget("1-1-1-1.sslip.io", 443)), connector.targets, "pide una IP pública conocida por el nombre del servicio")
        assertEquals(listOf("clave"), connector.passwords)
        assertTrue(tunnel.closed, "el túnel de prueba no se deja abierto")
    }

    @Test
    fun `each service is asked for with its own template`() = runTest {
        val connector = FakeProxyConnector.connectingTo(FakeTunnelConnection())

        tester(connector).test(IpNameService.NIP, profile, "")
        tester(connector).test(IpNameService("x", "Mío", "ip-{ip-}.mio.com"), profile, "")

        assertEquals(listOf("1.1.1.1.nip.io", "ip-1-1-1-1.mio.com"), connector.targets.map { it.host })
    }

    @Test
    fun `a refusal is reported with the proxy's own words`() = runTest {
        val result = tester(FakeProxyConnector.failingWith(ProxyFailure.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"))).test(IpNameService.SSLIP, profile, "")

        assertEquals(NameServiceTestResult.Refused(RefusalReason.FORBIDDEN, "HTTP/1.1 403 Forbidden"), result)
    }

    @Test
    fun `rejected credentials say nothing about the service`() = runTest {
        listOf(ProxyFailure.AuthRejected, ProxyFailure.AuthRequired, ProxyFailure.UnsupportedAuth(listOf("Negotiate"))).forEach {
            assertEquals(NameServiceTestResult.ProxyAuth, tester(FakeProxyConnector.failingWith(it)).test(IpNameService.SSLIP, profile, ""))
        }
    }

    @Test
    fun `a network failure is reported as such`() = runTest {
        val result = tester(FakeProxyConnector.failingWith(ProxyFailure.Unreachable(NetFailure.NO_ROUTE, "sin ruta"))).test(IpNameService.SSLIP, profile, "")

        assertEquals(NameServiceTestResult.Unreachable(NetFailure.NO_ROUTE, "sin ruta"), result)
    }

    @Test
    fun `a protocol with no connector cannot be tested`() = runTest {
        val socks = profile.copy(protocol = ProxyProtocol.SOCKS5)

        val result = tester(FakeProxyConnector.connectingTo(FakeTunnelConnection())).test(IpNameService.SSLIP, socks, "")

        assertTrue(result is NameServiceTestResult.Unreachable)
    }
}
