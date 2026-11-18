package org.uci.proxy.core.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.platform.CommandResult
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.platform.PacketChannel
import org.uci.proxy.core.platform.windows.WinNetwork
import org.uci.proxy.core.platform.windows.WinPlatformTunnel
import org.uci.proxy.core.platform.windows.WindowsNetworkException
import org.uci.proxy.core.platform.windows.WintunOpener
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelUnavailableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ROUTE_PRINT = """
===========================================================================
Interface List
  7...00 15 5d 01 02 03 ......Hyper-V Virtual Ethernet Adapter
===========================================================================

IPv4 Route Table
===========================================================================
Active Routes:
Network Destination        Netmask          Gateway       Interface  Metric
          0.0.0.0          0.0.0.0      192.168.1.254   192.168.1.50     45
          0.0.0.0          0.0.0.0      192.168.1.1     192.168.1.50     25
        127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
===========================================================================
""".trimIndent()

private const val FLUSH = "ipconfig /flushdns"

/** Anota cada orden y responde con salidas enlatadas; [failOn] hace fallar la primera que lo contenga. */
private class FakeRunner(var failOn: String? = null) : CommandRunner {
    private val all = mutableListOf<String>()

    override fun run(command: List<String>, input: String?): CommandResult {
        val line = command.joinToString(" ")
        all += line
        return when {
            failOn?.let { it in line } == true -> CommandResult(1, "The requested operation requires elevation.")
            line.startsWith("route print") -> CommandResult(0, ROUTE_PRINT)
            else -> CommandResult(0, "Ok.")
        }
    }

    /** Solo las órdenes que cambian algo. */
    val changes: List<String> get() = all.filterNot { it.startsWith("route print") }
}

private class FakeChannel : PacketChannel {
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

class WinNetworkTest {
    private val runner = FakeRunner()
    private val network = WinNetwork(runner)

    @Test
    fun `the default gateway is the one of the default route with the lowest metric`() {
        assertEquals("192.168.1.1", network.defaultGateway())
    }

    @Test
    fun `without a default route there is no gateway`() {
        val offline = WinNetwork { _, _ -> CommandResult(0, "Active Routes:\n        127.0.0.0        255.0.0.0         On-link         127.0.0.1    331") }

        assertNull(offline.defaultGateway())
    }

    @Test
    fun `the adapter gets its address, its DNS and the lowest metric`() {
        network.configureAdapter("ProxyUCI", "10.57.0.1", "10.57.0.2", 1500)

        assertEquals(
            listOf(
                "netsh interface ip set address name=ProxyUCI source=static addr=10.57.0.1 mask=255.255.0.0 gateway=none",
                "netsh interface ip set dns name=ProxyUCI source=static addr=10.57.0.2 validate=no",
                "netsh interface ipv4 set subinterface ProxyUCI mtu=1500 store=active",
                "netsh interface ipv4 set interface ProxyUCI metric=1",
            ),
            runner.changes,
        )
    }

    @Test
    fun `the adapter takes the netmask the VPN server pushed`() {
        network.configureAdapter("ProxyUCI", "10.8.0.6", "10.8.0.1", 1500, mask = "255.255.255.0")

        assertEquals(
            "netsh interface ip set address name=ProxyUCI source=static addr=10.8.0.6 mask=255.255.255.0 gateway=none",
            runner.changes.first(),
        )
        assertFailsWith<IllegalArgumentException> { network.configureAdapter("ProxyUCI", "10.8.0.6", "10.8.0.1", 1500, mask = "255.255.255.0 & calc") }
    }

    @Test
    fun `routes go through the adapter or through the real gateway`() {
        network.addRouteThroughAdapter(Ipv4Route.parse("8.0.0.0/7"), "ProxyUCI")
        network.addHostRouteThroughGateway("200.55.1.2", "192.168.1.1")
        network.removeHostRoute("200.55.1.2")
        network.flushDns()

        assertEquals(
            listOf(
                "netsh interface ipv4 add route prefix=8.0.0.0/7 interface=ProxyUCI nexthop=0.0.0.0 metric=1 store=active",
                "route add 200.55.1.2 mask 255.255.255.255 192.168.1.1 metric 1",
                "route delete 200.55.1.2",
                FLUSH,
            ),
            runner.changes,
        )
    }

    @Test
    fun `values that could alter the commands are rejected`() {
        assertFailsWith<IllegalArgumentException> { network.configureAdapter("Proxy UCI", "10.57.0.1", "10.57.0.2", 1500) }
        assertFailsWith<IllegalArgumentException> { network.configureAdapter("ProxyUCI", "10.57.0.1 & calc", "10.57.0.2", 1500) }
        assertFailsWith<IllegalArgumentException> { network.addRouteThroughAdapter(Ipv4Route.parse("8.0.0.0/7"), "x\" & del") }
        assertFailsWith<IllegalArgumentException> { network.addHostRouteThroughGateway("200.55.1.2", "gw") }
        assertTrue(runner.changes.isEmpty())
    }

    @Test
    fun `a command that fails is reported with its output`() {
        runner.failOn = "netsh"

        val error = assertFailsWith<WindowsNetworkException> { network.configureAdapter("ProxyUCI", "10.57.0.1", "10.57.0.2", 1500) }

        assertTrue("requires elevation" in error.message.orEmpty())
    }
}

class WinPlatformTunnelTest {
    private val runner = FakeRunner()
    private val channel = FakeChannel()
    private var openFails = false

    private fun tunnel() = WinPlatformTunnel(
        network = WinNetwork(runner),
        wintunOpener = WintunOpener { if (openFails) throw WindowsNetworkException("Acceso denegado") else channel },
        ioDispatcher = Dispatchers.Unconfined,
        logger = RecordingLogger(),
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
    fun `the tunnel is set up in order and the DNS cache is flushed at the end`() = runBlocking {
        tunnel().establish(config())

        assertEquals(
            listOf(
                "netsh interface ip set address name=ProxyUCI source=static addr=10.57.0.1 mask=255.255.0.0 gateway=none",
                "netsh interface ip set dns name=ProxyUCI source=static addr=10.57.0.2 validate=no",
                "netsh interface ipv4 set subinterface ProxyUCI mtu=1500 store=active",
                "netsh interface ipv4 set interface ProxyUCI metric=1",
                "netsh interface ipv4 add route prefix=10.57.0.0/16 interface=ProxyUCI nexthop=0.0.0.0 metric=1 store=active",
                "netsh interface ipv4 add route prefix=8.0.0.0/7 interface=ProxyUCI nexthop=0.0.0.0 metric=1 store=active",
                "netsh interface ipv4 add route prefix=200.0.0.0/8 interface=ProxyUCI nexthop=0.0.0.0 metric=1 store=active",
                FLUSH,
            ),
            runner.changes,
        )
    }

    @Test
    fun `the DNS servers of the network are never routed into the tunnel`() = runBlocking {
        tunnel().establish(config())

        assertFalse(runner.changes.any { "192.168.1.1" in it })
    }

    @Test
    fun `a proxy on a private network needs no route of its own`() = runBlocking {
        tunnel().establish(config(proxyAddress = "10.8.0.20"))

        assertFalse(runner.changes.any { it.startsWith("route add") })
    }

    @Test
    fun `a proxy on the public internet is routed through the real gateway before the tunnel routes`() = runBlocking {
        tunnel().establish(config(proxyAddress = "200.55.1.2"))

        val bypass = runner.changes.indexOf("route add 200.55.1.2 mask 255.255.255.255 192.168.1.1 metric 1")
        val tunnelRoute = runner.changes.indexOfFirst { "prefix=200.0.0.0/8" in it }
        assertTrue(bypass in 0 until tunnelRoute)
    }

    @Test
    fun `closing closes the channel and removes the proxy route once`() = runBlocking {
        val device = tunnel().establish(config(proxyAddress = "200.55.1.2"))
        val before = runner.changes.size

        device.close()
        device.close()

        assertTrue(channel.closed)
        assertEquals(listOf("route delete 200.55.1.2", FLUSH), runner.changes.drop(before))
    }

    @Test
    fun `not being able to create the adapter is reported as unavailable`() {
        openFails = true

        val error = assertFailsWith<TunnelUnavailableException> { runBlocking { tunnel().establish(config()) } }

        assertTrue("Acceso denegado" in error.message.orEmpty())
        assertTrue(runner.changes.isEmpty())
    }

    @Test
    fun `a failure half way closes the adapter and removes the proxy route`() {
        runner.failOn = "prefix=8.0.0.0/7"

        assertFailsWith<TunnelUnavailableException> {
            runBlocking { tunnel().establish(config(proxyAddress = "200.55.1.2")) }
        }

        assertTrue(channel.closed)
        assertEquals(listOf("route delete 200.55.1.2", FLUSH), runner.changes.takeLast(2))
    }

    @Test
    fun `packets pass through to and from the adapter`() = runBlocking {
        val device = tunnel().establish(config())
        channel.incoming += byteArrayOf(0x45, 1, 2, 3)

        assertContentEquals(byteArrayOf(0x45, 1, 2, 3), device.readPacket())
        device.writePacket(byteArrayOf(0x45, 9))
        assertContentEquals(byteArrayOf(0x45, 9), channel.written.single())

        device.close()
        assertNull(device.readPacket())
    }
}
