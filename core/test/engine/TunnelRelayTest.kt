package org.uci.proxy.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.proxy.IpNamer
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.tcp.TcpTestClient
import org.uci.proxy.core.tcp.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRelayTest {
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val tunnel = FakeTunnelConnection()

    private fun TestScope.appThrough(
        connector: FakeProxyConnector,
        serverIp: String = "93.184.216.34",
        serverPort: Int = 443,
        answerChecks: Boolean = false,
        health: ProxyHealth = ProxyHealth(testScheduler.timeSource),
        namer: IpNamer = IpNamer(AppSettings()),
    ): TcpTestClient {
        val checks = ConnectivityChecks(answerChecks, health)
        val relay = TunnelRelay(connector, profile, password = "secreta", logger = RecordingLogger(), health, checks, namer)
        return TcpTestClient(this, serverIp = parseIpv4(serverIp)!!, serverPort = serverPort, onAccept = relay::serve)
    }

    private val androidCheck = "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n"

    @Test
    fun `a connection to a fake DNS address is opened by name with the profile credentials`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector)

        app.handshake(destinationHost = "www.uci.cu")

        assertEquals(listOf(ProxyTarget("www.uci.cu", 443)), connector.targets)
        assertEquals(listOf("secreta"), connector.passwords)
    }

    @Test
    fun `bytes flow from the app to the proxy and back`() = runTest {
        val app = appThrough(FakeProxyConnector.connectingTo(tunnel))
        app.handshake(destinationHost = "www.uci.cu")

        app.sendData("GET / HTTP/1.1\r\n\r\n")
        tunnel.deliver("HTTP/1.1 200 OK\r\n\r\n")
        runCurrent()

        assertEquals("GET / HTTP/1.1\r\n\r\n", tunnel.writtenText)
        assertEquals("HTTP/1.1 200 OK\r\n\r\n", app.drain().joinToString("") { it.text() })
    }

    @Test
    fun `a connection to a raw IP is opened by the name found in its first bytes`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector)
        app.handshake()
        val request = "GET / HTTP/1.1\r\nHost: intranet.uci.cu\r\n\r\n"

        app.sendData(request)

        assertEquals(listOf(ProxyTarget("intranet.uci.cu", 443)), connector.targets)
        assertEquals(request, tunnel.writtenText, "lo leído para averiguar el nombre también se reenvía")
    }

    @Test
    fun `a raw public IP whose first bytes say nothing is opened through its sslip name`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(listOf(ProxyTarget("93-184-216-34.sslip.io", 443)), connector.targets)
        assertEquals("SSH-2.0-OpenSSH_9.6\r\n", tunnel.writtenText)
    }

    @Test
    fun `a raw public IP that stays silent is opened through its sslip name after a short wait`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector)
        app.handshake()
        assertTrue(connector.targets.isEmpty(), "espera un momento a los primeros bytes")

        advanceTimeBy(250)

        assertEquals(listOf(ProxyTarget("93-184-216-34.sslip.io", 443)), connector.targets)
    }

    @Test
    fun `when the proxy refuses the app connection is reset`() = runTest {
        val app = appThrough(FakeProxyConnector.failingWith(ProxyFailure.AuthRejected))

        app.handshake(destinationHost = "www.uci.cu")

        assertTrue(app.drain().last().has(TcpFlags.RST))
    }

    @Test
    fun `a public IP the proxy accepts by name is never asked for by address`() = runTest {
        // El Squid de la UCI rechaza el CONNECT a una IP pública escrita tal cual, pero acepta el nombre sslip.io.
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".sslip.io")) {
                ProxyConnectResult.Connected(tunnel)
            } else {
                ProxyConnectResult.Failed(ProxyFailure.Refused("HTTP/1.1 403 Forbidden"))
            }
        }
        val app = appThrough(connector)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(listOf(ProxyTarget("93-184-216-34.sslip.io", 443)), connector.targets, "sin pagar un 403 de más")
        assertEquals("SSH-2.0-OpenSSH_9.6\r\n", tunnel.writtenText)
    }

    @Test
    fun `when the proxy refuses every name the IP itself is tried`() = runTest {
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".sslip.io") || target.host.endsWith(".nip.io")) {
                ProxyConnectResult.Failed(ProxyFailure.Refused("HTTP/1.1 403 Forbidden"))
            } else {
                ProxyConnectResult.Connected(tunnel)
            }
        }
        val app = appThrough(connector)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(
            listOf(ProxyTarget("93-184-216-34.sslip.io", 443), ProxyTarget("93.184.216.34.nip.io", 443), ProxyTarget("93.184.216.34", 443)),
            connector.targets,
        )
        assertEquals("SSH-2.0-OpenSSH_9.6\r\n", tunnel.writtenText)
    }

    @Test
    fun `the next service is tried when the proxy refuses the first one`() = runTest {
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".nip.io")) ProxyConnectResult.Connected(tunnel) else ProxyConnectResult.Failed(ProxyFailure.Refused("403"))
        }
        val app = appThrough(connector)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(
            listOf(ProxyTarget("93-184-216-34.sslip.io", 443), ProxyTarget("93.184.216.34.nip.io", 443)),
            connector.targets,
        )
    }

    @Test
    fun `a service the user switched off is never asked`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused("403"))
        val settings = AppSettings(nameServices = listOf(IpNameService.SSLIP.copy(enabled = false), IpNameService.NIP))
        val app = appThrough(connector, namer = IpNamer(settings))
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(listOf(ProxyTarget("93.184.216.34.nip.io", 443), ProxyTarget("93.184.216.34", 443)), connector.targets)
    }

    @Test
    fun `with names turned off the IP goes as it is`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector, namer = IpNamer(AppSettings(nameByIp = false)))
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(listOf(ProxyTarget("93.184.216.34", 443)), connector.targets)
    }

    @Test
    fun `a timeout or a network failure does not try the next service`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Unreachable("sin ruta"))
        val app = appThrough(connector)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(1, connector.targets.size, "solo un rechazo del proxy justifica otro nombre")
    }

    @Test
    fun `the relay reports which name services were refused and which worked`() = runTest {
        val reports = mutableListOf<Pair<String, NameOutcome>>()
        val namer = IpNamer(AppSettings(), { id, outcome -> reports += id to outcome }, testScheduler.timeSource)
        val connector = FakeProxyConnector { target ->
            if (target.host.endsWith(".nip.io")) ProxyConnectResult.Connected(tunnel) else ProxyConnectResult.Failed(ProxyFailure.Refused("403"))
        }
        val app = appThrough(connector, namer = namer)
        app.handshake()

        app.sendData("SSH-2.0-OpenSSH_9.6\r\n")

        assertEquals(listOf("sslip" to NameOutcome.REFUSED, "nip" to NameOutcome.WORKED), reports)
    }

    @Test
    fun `a refused host name is not retried`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused("HTTP/1.1 403 Forbidden"))
        val app = appThrough(connector)

        app.handshake(destinationHost = "bloqueado.example")

        assertEquals(1, connector.targets.size)
        assertTrue(app.drain().last().has(TcpFlags.RST))
    }

    @Test
    fun `a refused private IP is not retried through sslip`() = runTest {
        val connector = FakeProxyConnector.failingWith(ProxyFailure.Refused("HTTP/1.1 403 Forbidden"))
        val app = appThrough(connector, serverIp = "192.168.1.10")
        app.handshake()

        advanceTimeBy(250)

        assertEquals(listOf(ProxyTarget("192.168.1.10", 443)), connector.targets)
    }

    @Test
    fun `when the app finishes sending the proxy is told and the reply still arrives`() = runTest {
        val app = appThrough(FakeProxyConnector.connectingTo(tunnel))
        app.handshake(destinationHost = "www.uci.cu")
        app.sendData("peticion", flags = TcpFlags.ACK or TcpFlags.FIN)
        app.sequence += 1
        app.drain()

        assertTrue(tunnel.outputShutDown)
        assertFalse(tunnel.closed, "la respuesta todavía puede llegar")

        tunnel.deliver("respuesta")
        tunnel.end()
        runCurrent()

        val segments = app.drain()
        assertEquals("respuesta", segments.joinToString("") { it.text() })
        assertTrue(segments.last().has(TcpFlags.FIN))
        assertTrue(tunnel.closed)
    }

    @Test
    fun `when the server finishes the app sees an orderly close`() = runTest {
        val app = appThrough(FakeProxyConnector.connectingTo(tunnel))
        app.handshake(destinationHost = "www.uci.cu")

        tunnel.deliver("adios")
        tunnel.end()
        runCurrent()

        val segments = app.drain()
        assertEquals("adios", segments.first().text())
        assertTrue(segments.last().has(TcpFlags.FIN))
        assertFalse(segments.any { it.has(TcpFlags.RST) })
    }

    @Test
    fun `a broken proxy connection resets the app connection`() = runTest {
        val app = appThrough(FakeProxyConnector.connectingTo(tunnel))
        app.handshake(destinationHost = "www.uci.cu")

        tunnel.fail()
        runCurrent()

        assertTrue(app.drain().last().has(TcpFlags.RST))
        assertTrue(tunnel.closed)
    }

    @Test
    fun `a proxy that never answers the connect is given up on`() = runTest {
        val connector = FakeProxyConnector { kotlinx.coroutines.awaitCancellation() }
        val app = appThrough(connector)
        app.handshake(destinationHost = "www.uci.cu")

        advanceTimeBy(16_000)

        assertTrue(app.drain().last().has(TcpFlags.RST))
    }

    @Test
    fun `a connectivity check is answered locally when the proxy has worked recently`() = runTest {
        val health = ProxyHealth(testScheduler.timeSource).also { it.markSuccess() }
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector, serverPort = 80, answerChecks = true, health = health)
        app.handshake(destinationHost = "connectivitycheck.gstatic.com")

        app.sendData(androidCheck)
        runCurrent()

        assertTrue(connector.targets.isEmpty(), "no se molesta al proxy")
        assertTrue(app.drain().joinToString("") { it.text() }.startsWith("HTTP/1.1 204 No Content"))
    }

    @Test
    fun `a connectivity check goes to the proxy when it has not worked yet`() = runTest {
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector, serverPort = 80, answerChecks = true)
        app.handshake(destinationHost = "connectivitycheck.gstatic.com")

        app.sendData(androidCheck)
        runCurrent()

        assertEquals(listOf(ProxyTarget("connectivitycheck.gstatic.com", 80)), connector.targets)
        assertEquals(androidCheck, tunnel.writtenText, "la petición leída para reconocerla también se reenvía")
    }

    @Test
    fun `a connectivity check goes to the proxy when the setting is off`() = runTest {
        val health = ProxyHealth(testScheduler.timeSource).also { it.markSuccess() }
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector, serverPort = 80, answerChecks = false, health = health)
        app.handshake(destinationHost = "connectivitycheck.gstatic.com")

        app.sendData(androidCheck)
        runCurrent()

        assertEquals(1, connector.targets.size)
    }

    @Test
    fun `other requests to a check host are not answered locally`() = runTest {
        val health = ProxyHealth(testScheduler.timeSource).also { it.markSuccess() }
        val connector = FakeProxyConnector.connectingTo(tunnel)
        val app = appThrough(connector, serverPort = 80, answerChecks = true, health = health)
        app.handshake(destinationHost = "www.google.com")

        app.sendData("GET /search?q=uci HTTP/1.1\r\nHost: www.google.com\r\n\r\n")
        runCurrent()

        assertEquals(listOf(ProxyTarget("www.google.com", 80)), connector.targets)
    }

    @Test
    fun `a connection the proxy opens makes it count as working`() = runTest {
        val health = ProxyHealth(testScheduler.timeSource)
        val app = appThrough(FakeProxyConnector.connectingTo(tunnel), health = health)

        app.handshake(destinationHost = "www.uci.cu")

        assertTrue(health.isHealthy())
    }

    @Test
    fun `a connection the proxy refuses does not make it count as working`() = runTest {
        val health = ProxyHealth(testScheduler.timeSource)
        val app = appThrough(FakeProxyConnector.failingWith(ProxyFailure.Refused("HTTP/1.1 403 Forbidden")), health = health)

        app.handshake(destinationHost = "bloqueado.example")

        assertFalse(health.isHealthy())
    }
}
