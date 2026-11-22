package org.uci.proxy.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.dns.dnsQuery
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunDevice
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.data.StoredProfileRepository
import org.uci.proxy.core.proxy.ProxyTarget
import kotlin.test.Test
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.traffic.TrafficMeter
import org.uci.proxy.core.model.TrafficUsage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val APP = parseIpv4("10.57.0.1")!!
private val DNS = parseIpv4("10.57.0.2")!!
private val PROFILE = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelEngineTest {
    private val plan = TunAddressPlan(subnetBase = parseIpv4("10.57.0.0")!!)
    private val tun = FakeTunDevice()
    private val tunnel = FakeTunnelConnection()
    private val connector = FakeProxyConnector.connectingTo(tunnel)

    /** Arranca el motor sobre la TUN falsa; a partir de ahí la prueba inyecta paquetes con [send]. */
    private fun TestScope.startEngine(meter: TrafficMeter? = null) {
        val engine = TunnelEngineFactory(listOf(connector), { ByteArray(it) { 1 } }, testScheduler.timeSource, RecordingLogger())
            .create(plan, output = tun, scope = backgroundScope, profile = PROFILE, password = "secreta", meter = meter)
        backgroundScope.launch { engine.run(tun) }
    }

    private fun TestScope.send(packet: ByteArray) {
        tun.inject(packet)
        runCurrent()
    }

    private fun lastWritten() = assertNotNull(Ipv4Packet.parse(tun.written.last()))

    private fun dnsAnswerFor(name: String, scope: TestScope): Int {
        scope.send(buildUdpPacket(APP, DNS, sourcePort = 41000, destinationPort = 53, payload = dnsQuery(name)))
        val dns = UdpDatagram.parse(lastWritten())!!.payload
        return dns.readInt(dns.size - 4)
    }

    @Test
    fun `a DNS query is answered back to the app that asked`() = runTest {
        startEngine()

        send(buildUdpPacket(APP, DNS, sourcePort = 41000, destinationPort = 53, payload = dnsQuery("www.uci.cu")))

        val reply = lastWritten()
        val datagram = assertNotNull(UdpDatagram.parse(reply))
        assertEquals("10.57.0.2", formatIpv4(reply.source))
        assertEquals("10.57.0.1", formatIpv4(reply.destination))
        assertEquals(53, datagram.sourcePort)
        assertEquals(41000, datagram.destinationPort)
        assertEquals(1, datagram.payload.readU16(6), "una respuesta")
        assertEquals("10.57.1.0", formatIpv4(datagram.payload.readInt(datagram.payload.size - 4)))
    }

    @Test
    fun `two names get different addresses and a repeated name the same one`() = runTest {
        startEngine()

        val addresses = listOf("a.test", "b.test", "a.test").map { formatIpv4(dnsAnswerFor(it, this)) }

        assertEquals(listOf("10.57.1.0", "10.57.1.1", "10.57.1.0"), addresses)
    }

    @Test
    fun `a malformed DNS query gets no reply and does not stop the engine`() = runTest {
        startEngine()

        send(buildUdpPacket(APP, DNS, 41000, 53, ByteArray(4)))
        assertTrue(tun.written.isEmpty())

        send(buildUdpPacket(APP, DNS, 41000, 53, dnsQuery("uci.cu")))
        assertEquals(1, tun.written.size)
    }

    @Test
    fun `other UDP is rejected with ICMP so apps fall back to TCP`() = runTest {
        startEngine()
        val quic = buildUdpPacket(APP, parseIpv4("142.250.1.1")!!, 50000, 443, ByteArray(1200))

        send(quic)

        val reply = lastWritten()
        assertEquals(1, reply.protocol, "ICMP")
        assertEquals("142.250.1.1", formatIpv4(reply.source))
        assertEquals("10.57.0.1", formatIpv4(reply.destination))
    }

    @Test
    fun `packets that are neither IPv4 nor a known protocol are dropped`() = runTest {
        startEngine()

        send(ByteArray(3))
        send(buildUdpPacket(APP, DNS, 1, 2, ByteArray(8)).also { it[9] = 47 }) // GRE

        assertTrue(tun.written.isEmpty())
    }

    @Test
    fun `a TCP connection to a name resolved through the tunnel reaches the proxy by that name`() = runTest {
        startEngine()
        val fakeAddress = dnsAnswerFor("www.uci.cu", this)

        send(buildTcpPacket(APP, fakeAddress, 40000, 443, sequence = 1000, acknowledgment = 0, TcpFlags.SYN, 65_535))
        val synAck = assertNotNull(TcpSegment.parse(lastWritten()))
        assertTrue(synAck.has(TcpFlags.SYN) && synAck.has(TcpFlags.ACK))
        send(buildTcpPacket(APP, fakeAddress, 40000, 443, 1001, synAck.sequence + 1, TcpFlags.ACK, 65_535))

        assertEquals(listOf(ProxyTarget("www.uci.cu", 443)), connector.targets)

        send(buildTcpPacket(APP, fakeAddress, 40000, 443, 1001, synAck.sequence + 1, TcpFlags.ACK, 65_535, "hola".encodeToByteArray()))
        assertEquals("hola", tunnel.writtenText)
    }

    @Test
    fun `the bytes of each connection through the proxy are counted by destination`() = runTest {
        val meter = TrafficMeter()
        startEngine(meter)
        val fakeAddress = dnsAnswerFor("www.uci.cu", this)
        send(buildTcpPacket(APP, fakeAddress, 40000, 443, sequence = 1000, acknowledgment = 0, TcpFlags.SYN, 65_535))
        val synAck = assertNotNull(TcpSegment.parse(lastWritten()))
        send(buildTcpPacket(APP, fakeAddress, 40000, 443, 1001, synAck.sequence + 1, TcpFlags.ACK, 65_535))

        send(buildTcpPacket(APP, fakeAddress, 40000, 443, 1001, synAck.sequence + 1, TcpFlags.ACK, 65_535, "hola".encodeToByteArray()))
        tunnel.deliver("respuesta")
        runCurrent()

        val delta = meter.drain(1_000)
        assertEquals(listOf(TrafficUsage(RouteExit.PROXY, "www.uci.cu", app = null, up = 4, down = 9)), delta.usage)
        assertEquals(1, delta.connections)
        assertEquals(0, meter.drain(1_000).up, "lo entregado no se vuelve a contar")
    }

    @Test
    fun `a TCP connection to a fake address nobody resolved is reset`() = runTest {
        startEngine()
        val stale = parseIpv4("10.57.200.7")!!

        send(buildTcpPacket(APP, stale, 40000, 443, sequence = 1000, acknowledgment = 0, TcpFlags.SYN, 65_535))

        val reset = assertNotNull(TcpSegment.parse(lastWritten()))
        assertTrue(reset.has(TcpFlags.RST))
        assertEquals(1001, reset.acknowledgment)
        assertTrue(connector.targets.isEmpty())
    }
}

class TunAddressPlannerTest {
    private fun planner(local: List<String>, randomByte: Int) = TunAddressPlanner(
        localAddresses = { local.map { parseIpv4(it)!! } },
        randomBytes = { ByteArray(it) { randomByte.toByte() } },
    )

    @Test
    fun `the plan uses a 10 dot A subnet with fixed interface and DNS addresses`() {
        val plan = planner(local = emptyList(), randomByte = 56).plan()

        assertEquals("10.57.0.0", plan.subnetText)
        assertEquals("10.57.0.1", plan.tunAddressText)
        assertEquals("10.57.0.2", plan.dnsServerText)
        assertEquals(Ipv4Route.parse("10.57.0.0/16"), plan.subnet)
    }

    @Test
    fun `second octets already used by a local 10 network are never chosen`() {
        val local = listOf("10.57.3.4", "10.8.0.1", "192.168.1.5")

        val chosen = (0..255).map { planner(local, it).plan().subnetBase ushr 16 and 0xFF }.toSet()

        assertFalse(57 in chosen)
        assertFalse(8 in chosen)
        assertEquals(252, chosen.size)
    }

    @Test
    fun `the second octet is never 0 or 255`() {
        val chosen = (0..255).map { planner(emptyList(), it).plan().subnetBase ushr 16 and 0xFF }

        assertEquals(1, chosen.min())
        assertEquals(254, chosen.max())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelRunnerTest {
    private val logger = RecordingLogger()
    private val stateStore = TunnelStateStore()
    private val platformTunnel = FakePlatformTunnel()
    private val connector = FakeProxyConnector.connectingTo(FakeTunnelConnection())

    /** Lo que "resuelve" el DNS real en estas pruebas. */
    private val resolvedHosts = mutableMapOf("proxy.uci.cu" to "10.8.0.20")

    private fun TestScope.newRunner(): Pair<TunnelRunner, StoredProfileRepository> {
        val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher(testScheduler))
        val repository = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger)
        val runner = TunnelRunner(
            profiles = repository,
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
            engineFactory = TunnelEngineFactory(listOf(connector), { ByteArray(it) { 1 } }, testScheduler.timeSource, logger),
            hostResolver = { host -> resolvedHosts[host] },
            stateStore = stateStore,
            dispatchers = dispatchers,
            logger = logger,
        )
        return runner to repository
    }

    @Test
    fun `the interface is configured from the plan and the profile name`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        val job = launch { runner.run(platformTunnel, PROFILE.id) }

        val config = assertNotNull(platformTunnel.config)
        assertEquals("UCI", config.sessionName)
        assertEquals("10.57.0.1", config.address)
        assertEquals("10.57.0.2", config.dnsServer)
        assertEquals(1500, config.mtu)
        assertEquals("10.8.0.20", config.proxyAddress, "la dirección real del proxy, resuelta antes de levantar el túnel")
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        job.cancel()
    }

    @Test
    fun `a proxy name that does not resolve fails before touching the network`() = runTest {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        resolvedHosts.clear()

        runner.run(platformTunnel, PROFILE.id)

        assertEquals(TunnelState.Failed(TunnelFailure.PROXY_HOST_UNRESOLVED), stateStore.state.value)
        assertEquals(null, platformTunnel.config)
    }

    @Test
    fun `a profile handed over directly runs without being stored`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, PROFILE, password = "secreta") }

        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        job.cancel()
    }

    @Test
    fun `the routes send the virtual subnet and the public internet into the tunnel but not private networks`() =
        runTest(UnconfinedTestDispatcher()) {
            val (runner, repository) = newRunner()
            repository.save(PROFILE, "")
            val job = launch { runner.run(platformTunnel, PROFILE.id) }

            val routes = assertNotNull(platformTunnel.config).routes
            fun routed(address: String) = routes.any { it.contains(parseIpv4(address)!!) }

            assertTrue(routed("10.57.1.5"), "direcciones del DNS falso")
            assertTrue(routed("8.8.8.8"))
            assertFalse(routed("10.8.0.1"), "otra red 10.x, como la institucional")
            assertFalse(routed("192.168.1.1"))
            job.cancel()
        }

    @Test
    fun `the running tunnel answers DNS`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        val job = launch { runner.run(platformTunnel, PROFILE.id) }

        platformTunnel.tun.inject(buildUdpPacket(APP, DNS, 41000, 53, dnsQuery("uci.cu")))

        assertEquals(1, platformTunnel.tun.written.size)
        job.cancel()
    }

    @Test
    fun `connections through the tunnel use the stored password`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "secreta")
        val job = launch { runner.run(platformTunnel, PROFILE.id) }
        val tun = platformTunnel.tun
        tun.inject(buildUdpPacket(APP, DNS, 41000, 53, dnsQuery("uci.cu")))
        val dns = UdpDatagram.parse(Ipv4Packet.parse(tun.written.last())!!)!!.payload
        val fakeAddress = dns.readInt(dns.size - 4)

        tun.inject(buildTcpPacket(APP, fakeAddress, 40000, 443, 1000, 0, TcpFlags.SYN, 65_535))
        val synAck = TcpSegment.parse(Ipv4Packet.parse(tun.written.last())!!)!!
        tun.inject(buildTcpPacket(APP, fakeAddress, 40000, 443, 1001, synAck.sequence + 1, TcpFlags.ACK, 65_535))

        assertEquals(listOf("secreta"), connector.passwords)
        job.cancel()
    }

    @Test
    fun `cancelling closes the interface and ends disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        val job = launch { runner.run(platformTunnel, PROFILE.id) }

        job.cancel()
        job.join()

        assertTrue(platformTunnel.tun.closed)
        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `when the system closes the interface the tunnel ends disconnected`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        val job = launch { runner.run(platformTunnel, PROFILE.id) }

        platformTunnel.tun.close()
        job.join()

        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `an unknown profile fails without creating the interface`() = runTest {
        val (runner, _) = newRunner()

        runner.run(platformTunnel, "no-existe")

        assertEquals(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND), stateStore.state.value)
        assertEquals(null, platformTunnel.config)
    }

    @Test
    fun `a system that refuses the interface is reported`() = runTest {
        val (runner, repository) = newRunner()
        repository.save(PROFILE, "")
        platformTunnel.failWith = "VPN no preparada"

        runner.run(platformTunnel, PROFILE.id)

        assertEquals(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE), stateStore.state.value)
    }
}
