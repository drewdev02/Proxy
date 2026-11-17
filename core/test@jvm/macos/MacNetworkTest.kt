package org.uci.proxy.core.macos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.platform.CommandResult
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.platform.macos.MacNetwork
import org.uci.proxy.core.platform.macos.MacNetworkException
import org.uci.proxy.core.platform.macos.MacPlatformTunnel
import org.uci.proxy.core.platform.macos.UtunHandle
import org.uci.proxy.core.platform.macos.UtunOpener
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelUnavailableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ROUTE_GET_DEFAULT = """   route to: default
destination: default
       mask: default
    gateway: 192.168.1.1
  interface: en0
      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
"""

private const val REMOVE_RESOLVER = "scutil: remove State:/Network/Service/ProxyUCI/DNS"

/**
 * Anota cada orden y responde con salidas enlatadas; [failOn] hace fallar la primera que lo contenga.
 * Las órdenes de scutil que llevan guion se anotan como "scutil: <guion en una línea>".
 */
private class FakeRunner(var failOn: String? = null) : CommandRunner {
    private val all = mutableListOf<String>()

    override fun run(command: List<String>, input: String?): CommandResult {
        val line = if (input == null) {
            command.joinToString(" ")
        } else {
            "scutil: " + input.lines().filter { it.isNotBlank() && it != "quit" }.joinToString("; ")
        }
        all += line
        return when {
            failOn?.let { it in line } == true -> CommandResult(1, "route: writing to routing socket: Operation not permitted")
            "get default" in line -> CommandResult(0, ROUTE_GET_DEFAULT)
            else -> CommandResult(0, "")
        }
    }

    /** Solo las órdenes que cambian algo. */
    val changes: List<String> get() = all.filterNot { "get default" in it }
}

private class FakeUtun : UtunHandle {
    override val name = "utun9"
    val written = mutableListOf<ByteArray>()
    val incoming = ArrayDeque<ByteArray>()
    var closed = false

    override fun read(timeoutMs: Int): ByteArray? = incoming.removeFirstOrNull()

    override fun write(packet: ByteArray) {
        written += packet
    }

    override fun close() {
        closed = true
    }
}

class MacNetworkTest {
    private val runner = FakeRunner()
    private val network = MacNetwork(runner)

    @Test
    fun `the default gateway is read from the routing table`() {
        assertEquals("192.168.1.1", network.defaultGateway())
    }

    @Test
    fun `without a default route there is no gateway`() {
        val offline = MacNetwork { _, _ -> CommandResult(1, "route: writing to routing socket: not in table") }

        assertNull(offline.defaultGateway())
    }

    @Test
    fun `interface and routes are configured with the system tools`() {
        network.configureInterface("utun9", "10.57.0.1", 1500)
        network.addRouteThroughInterface(Ipv4Route.parse("10.57.0.0/16"), "utun9")
        network.addHostRouteThroughGateway("200.55.1.2", "192.168.1.1")
        network.removeHostRoute("200.55.1.2")

        assertEquals(
            listOf(
                "/sbin/ifconfig utun9 inet 10.57.0.1 10.57.0.1 netmask 255.255.255.255 mtu 1500 up",
                "/sbin/route -n add -net 10.57.0.0/16 -interface utun9",
                "/sbin/route -n add -host 200.55.1.2 192.168.1.1",
                "/sbin/route -n delete -host 200.55.1.2",
            ),
            runner.changes,
        )
    }

    @Test
    fun `a resolver for every domain is published with an empty match domain`() {
        network.publishDnsResolver("10.57.0.2", listOf(""))

        assertEquals(
            listOf(
                "scutil: d.init; d.add ServerAddresses * 10.57.0.2; d.add SupplementalMatchDomains * \"\"; " +
                    "d.add SupplementalMatchDomainsNoSearch # 1; set State:/Network/Service/ProxyUCI/DNS",
            ),
            runner.changes,
        )
    }

    @Test
    fun `a resolver can be limited to some domains and is removed by key`() {
        network.publishDnsResolver("10.57.0.2", listOf("sslip.io", "proxyuci.test"))
        network.removeDnsResolver()

        assertEquals(
            listOf(
                "scutil: d.init; d.add ServerAddresses * 10.57.0.2; d.add SupplementalMatchDomains * sslip.io proxyuci.test; " +
                    "d.add SupplementalMatchDomainsNoSearch # 1; set State:/Network/Service/ProxyUCI/DNS",
                REMOVE_RESOLVER,
            ),
            runner.changes,
        )
    }

    @Test
    fun `values that could inject commands into the scutil script are rejected`() {
        assertFailsWith<IllegalArgumentException> { network.publishDnsResolver("10.57.0.2", listOf("a.test\nremove State:/x")) }
        assertFailsWith<IllegalArgumentException> { network.publishDnsResolver("10.57.0.2; quit", listOf("")) }
        assertFailsWith<IllegalArgumentException> { network.publishDnsResolver("10.57.0.2", emptyList()) }
        assertTrue(runner.changes.isEmpty())
    }

    @Test
    fun `a command that fails is reported with its output`() {
        runner.failOn = "ifconfig"

        val error = assertFailsWith<MacNetworkException> { network.configureInterface("utun9", "10.57.0.1", 1500) }

        assertTrue("Operation not permitted" in error.message.orEmpty())
    }
}

class MacPlatformTunnelTest {
    private val runner = FakeRunner()
    private val utun = FakeUtun()
    private var openFails = false

    private fun tunnel(dnsDomains: List<String> = MacPlatformTunnel.ALL_DOMAINS) = MacPlatformTunnel(
        network = MacNetwork(runner),
        utunOpener = UtunOpener { if (openFails) throw MacNetworkException("errno 1") else utun },
        ioDispatcher = Dispatchers.Unconfined,
        logger = RecordingLogger(),
        dnsDomains = dnsDomains,
    )

    private fun config(proxyAddress: String = "10.8.0.20") = TunConfig(
        sessionName = "UCI",
        address = "10.57.0.1",
        dnsServer = "10.57.0.2",
        mtu = 1500,
        routes = listOf(Ipv4Route.parse("10.57.0.0/16"), Ipv4Route.parse("8.0.0.0/7"), Ipv4Route.parse("200.0.0.0/8")),
        proxyAddress = proxyAddress,
    )

    @Test
    fun `the tunnel is set up in order and ends by publishing its own DNS resolver`() = runBlocking {
        tunnel().establish(config())

        assertEquals(
            listOf(
                // Por si una ejecución anterior murió sin limpiar.
                REMOVE_RESOLVER,
                "/sbin/ifconfig utun9 inet 10.57.0.1 10.57.0.1 netmask 255.255.255.255 mtu 1500 up",
                "/sbin/route -n add -net 10.57.0.0/16 -interface utun9",
                "/sbin/route -n add -net 8.0.0.0/7 -interface utun9",
                "/sbin/route -n add -net 200.0.0.0/8 -interface utun9",
                "scutil: d.init; d.add ServerAddresses * 10.57.0.2; d.add SupplementalMatchDomains * \"\"; " +
                    "d.add SupplementalMatchDomainsNoSearch # 1; set State:/Network/Service/ProxyUCI/DNS",
            ),
            runner.changes,
        )
    }

    @Test
    fun `a proxy on a private network needs no route of its own`() = runBlocking {
        tunnel().establish(config(proxyAddress = "10.8.0.20"))

        assertFalse(runner.changes.any { "10.8.0.20" in it })
    }

    @Test
    fun `a proxy on the public internet is routed through the real gateway before the tunnel routes`() = runBlocking {
        tunnel().establish(config(proxyAddress = "200.55.1.2"))

        val bypass = runner.changes.indexOf("/sbin/route -n add -host 200.55.1.2 192.168.1.1")
        val tunnelRoute = runner.changes.indexOf("/sbin/route -n add -net 200.0.0.0/8 -interface utun9")
        assertTrue(bypass in 0 until tunnelRoute)
    }

    @Test
    fun `closing destroys the interface and removes the resolver and the proxy route once`() = runBlocking {
        val device = tunnel().establish(config(proxyAddress = "200.55.1.2"))
        val before = runner.changes.size

        device.close()
        device.close()

        assertTrue(utun.closed)
        assertEquals(listOf(REMOVE_RESOLVER, "/sbin/route -n delete -host 200.55.1.2"), runner.changes.drop(before))
    }

    @Test
    fun `the DNS servers of the network are never routed into the tunnel`() = runBlocking {
        // En muchas redes el servidor DNS es la propia puerta de enlace (aquí 192.168.1.1).
        tunnel().establish(config())

        assertFalse(runner.changes.any { "192.168.1.1" in it })
    }

    @Test
    fun `capturing only some domains publishes just those`() = runBlocking {
        tunnel(dnsDomains = listOf("sslip.io")).establish(config())

        assertTrue(runner.changes.last().contains("SupplementalMatchDomains * sslip.io;"))
    }

    @Test
    fun `without DNS capture nothing is published or diverted`() = runBlocking {
        tunnel(dnsDomains = emptyList()).establish(config())

        assertFalse(runner.changes.any { "SupplementalMatchDomains" in it })
    }

    @Test
    fun `not being able to open the interface is reported as unavailable`() {
        openFails = true

        assertFailsWith<TunnelUnavailableException> { runBlocking { tunnel().establish(config()) } }
        assertEquals(listOf(REMOVE_RESOLVER), runner.changes)
    }

    @Test
    fun `a failure half way closes the interface and undoes the resolver and the proxy route`() {
        runner.failOn = "-net 8.0.0.0/7"

        assertFailsWith<TunnelUnavailableException> {
            runBlocking { tunnel().establish(config(proxyAddress = "200.55.1.2")) }
        }

        assertTrue(utun.closed)
        assertEquals(listOf(REMOVE_RESOLVER, "/sbin/route -n delete -host 200.55.1.2"), runner.changes.takeLast(2))
    }

    @Test
    fun `packets pass through to and from the interface`() = runBlocking {
        val device = tunnel().establish(config())
        utun.incoming += byteArrayOf(0x45, 1, 2, 3)

        assertContentEquals(byteArrayOf(0x45, 1, 2, 3), device.readPacket())
        device.writePacket(byteArrayOf(0x45, 9))
        assertContentEquals(byteArrayOf(0x45, 9), utun.written.single())

        device.close()
        assertNull(device.readPacket())
    }
}
