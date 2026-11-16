package org.uci.proxy.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.dns.dnsQuery
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeTunDevice
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.PROTOCOL_TCP
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.StoredProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val APP = parseIpv4("10.57.0.1")!!
private val DNS = parseIpv4("10.57.0.2")!!

class TunnelEngineTest {
    private val plan = TunAddressPlan(subnetBase = parseIpv4("10.57.0.0")!!)
    private val logger = RecordingLogger()
    private val tun = FakeTunDevice()

    private suspend fun runWith(vararg packets: ByteArray) {
        packets.forEach(tun::inject)
        tun.close()
        TunnelEngineFactory(logger).create(plan).run(tun)
    }

    @Test
    fun `a DNS query is answered back to the app that asked`() = runTest {
        runWith(buildUdpPacket(APP, DNS, sourcePort = 41000, destinationPort = 53, payload = dnsQuery("www.uci.cu")))

        val reply = assertNotNull(Ipv4Packet.parse(tun.written.single()))
        val datagram = assertNotNull(UdpDatagram.parse(reply))
        assertEquals("10.57.0.2", formatIpv4(reply.source))
        assertEquals("10.57.0.1", formatIpv4(reply.destination))
        assertEquals(53, datagram.sourcePort)
        assertEquals(41000, datagram.destinationPort)

        val dns = datagram.payload
        assertEquals(1, dns.readU16(6), "una respuesta")
        assertEquals("10.57.1.0", formatIpv4(dns.readInt(dns.size - 4)), "primera dirección falsa")
    }

    @Test
    fun `two names get different addresses and a repeated name the same one`() = runTest {
        fun query(name: String) = buildUdpPacket(APP, DNS, 41000, 53, dnsQuery(name))

        runWith(query("a.test"), query("b.test"), query("a.test"))

        val addresses = tun.written.map { packet ->
            val dns = UdpDatagram.parse(Ipv4Packet.parse(packet)!!)!!.payload
            formatIpv4(dns.readInt(dns.size - 4))
        }
        assertEquals(listOf("10.57.1.0", "10.57.1.1", "10.57.1.0"), addresses)
    }

    @Test
    fun `traffic nobody handles is dropped`() = runTest {
        val udpToOtherPort = buildUdpPacket(APP, DNS, 41000, 123, ByteArray(8))
        val tcp = buildUdpPacket(APP, DNS, 41000, 53, ByteArray(20)).also { it[9] = PROTOCOL_TCP.toByte() }

        runWith(udpToOtherPort, tcp, ByteArray(3))

        assertTrue(tun.written.isEmpty())
    }

    @Test
    fun `a malformed DNS query gets no reply and does not stop the engine`() = runTest {
        runWith(
            buildUdpPacket(APP, DNS, 41000, 53, ByteArray(4)),
            buildUdpPacket(APP, DNS, 41000, 53, dnsQuery("uci.cu")),
        )

        assertEquals(1, tun.written.size)
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
    private val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher())
    private val logger = RecordingLogger()
    private val repository = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger)
    private val stateStore = TunnelStateStore()
    private val platformTunnel = FakePlatformTunnel()
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

    private val runner = TunnelRunner(
        profiles = repository,
        addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
        engineFactory = TunnelEngineFactory(logger),
        stateStore = stateStore,
        dispatchers = dispatchers,
        logger = logger,
    )

    @Test
    fun `the interface is configured from the plan and the profile name`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(profile, "")
        val job = launch { runner.run(platformTunnel, profile.id) }

        assertEquals(
            TunConfig(sessionName = "UCI", address = "10.57.0.1", virtualSubnet = "10.57.0.0", dnsServer = "10.57.0.2", mtu = 1500),
            platformTunnel.config,
        )
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        job.cancel()
    }

    @Test
    fun `the running tunnel answers DNS`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(profile, "")
        val job = launch { runner.run(platformTunnel, profile.id) }

        platformTunnel.tun.inject(buildUdpPacket(APP, DNS, 41000, 53, dnsQuery("uci.cu")))

        assertEquals(1, platformTunnel.tun.written.size)
        job.cancel()
    }

    @Test
    fun `cancelling closes the interface and ends disconnected`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(profile, "")
        val job = launch { runner.run(platformTunnel, profile.id) }

        job.cancel()
        job.join()

        assertTrue(platformTunnel.tun.closed)
        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `when the system closes the interface the tunnel ends disconnected`() = runTest(UnconfinedTestDispatcher()) {
        repository.save(profile, "")
        val job = launch { runner.run(platformTunnel, profile.id) }

        platformTunnel.tun.close()
        job.join()

        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `an unknown profile fails without creating the interface`() = runTest {
        runner.run(platformTunnel, "no-existe")

        assertEquals(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND), stateStore.state.value)
        assertEquals(null, platformTunnel.config)
    }

    @Test
    fun `a system that refuses the interface is reported`() = runTest {
        repository.save(profile, "")
        platformTunnel.failWith = "VPN no preparada"

        runner.run(platformTunnel, profile.id)

        assertEquals(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE), stateStore.state.value)
    }
}
