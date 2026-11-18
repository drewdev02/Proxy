package org.uci.proxy.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.VpnProfile
import org.uci.proxy.core.model.VpnSecrets
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.vpn.OpenVpnPush
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnExit
import org.uci.proxy.core.vpn.VpnSession
import org.uci.proxy.core.vpn.VpnStart
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun push(dns: List<String> = listOf("10.98.0.1")) = OpenVpnPush(
    address = "10.98.0.9", netmask = "255.255.0.0", dnsServers = dns, peerId = 3, cipher = "AES-256-GCM",
    tunMtu = 1500, pingSeconds = 10, pingRestartSeconds = 60, redirectGateway = true,
)

/** Una sesión VPN en memoria: lo que la prueba meta en [fromServer] llega a la interfaz, y lo que la interfaz envíe queda en [sent]. */
private class FakeVpnSession(override val push: OpenVpnPush = push()) : VpnSession {
    val sent = mutableListOf<ByteArray>()
    val fromServer = Channel<ByteArray>(Channel.UNLIMITED)
    var closed = false
        private set

    override suspend fun writePacket(packet: ByteArray) {
        sent += packet
    }

    override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) {
        for (packet in fromServer) onPacket(packet)
    }

    override fun close() {
        closed = true
        fromServer.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelVpnTest {
    private val logger = RecordingLogger()
    private val stateStore = TunnelStateStore()
    private val platformTunnel = FakePlatformTunnel()
    private val resolvedHosts = mutableMapOf("proxy.uci.cu" to "10.8.0.20")
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey", VpnProfile("pais.ovpn", "vpnuser"))
    private val start = VpnStart("client\n", "vpnuser", "vpnpass")

    private var result: VpnConnectResult = VpnConnectResult.Connected(FakeVpnSession())
    private val exitCalls = mutableListOf<Triple<ProxyProfile, String, VpnStart>>()
    private val exit = VpnExit { proxy, password, vpn ->
        exitCalls += Triple(proxy, password, vpn)
        result
    }

    private fun TestScope.newRunner(withExit: Boolean = true): Pair<TunnelRunner, StoredProfileRepository> {
        val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher(testScheduler))
        val repository = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger)
        val runner = TunnelRunner(
            profiles = repository,
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
            engineFactory = TunnelEngineFactory(listOf(FakeProxyConnector.connectingTo(FakeTunnelConnection())), { ByteArray(it) { 1 } }, testScheduler.timeSource, logger),
            hostResolver = { host -> resolvedHosts[host] },
            stateStore = stateStore,
            dispatchers = dispatchers,
            logger = logger,
            vpnExit = if (withExit) exit else null,
        )
        return runner to repository
    }

    private fun session() = (result as VpnConnectResult.Connected).session as FakeVpnSession

    private fun ipv4Packet(version: Int = 4) = ByteArray(40).also { it[0] = ((version shl 4) or 5).toByte() }

    @Test
    fun `the VPN is opened through the proxy with its resolved address and both passwords`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "proxypass", vpn = start) }

        val (proxy, password, vpn) = exitCalls.single()
        assertEquals("10.8.0.20", proxy.host, "la dirección real del proxy")
        assertEquals("proxypass", password)
        assertEquals(start, vpn)
        job.cancel()
    }

    @Test
    fun `the interface takes its address and DNS from the server and routes its network and the internet`() =
        runTest(UnconfinedTestDispatcher()) {
            val (runner, _) = newRunner()
            val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

            val config = assertNotNull(platformTunnel.config)
            assertEquals("10.98.0.9", config.address)
            assertEquals("10.98.0.1", config.dnsServer)
            assertEquals(1500, config.mtu)
            assertEquals("10.8.0.20", config.proxyAddress)
            assertEquals("255.255.0.0", config.netmask, "la máscara que empuja el servidor")
            fun routed(address: String) = config.routes.any { it.contains(parseIpv4(address)!!) }
            assertTrue(routed("10.98.0.1"), "el DNS de la VPN vive en su red")
            assertTrue(routed("8.8.8.8"))
            assertFalse(routed("10.8.0.1"), "la red del proxy queda fuera, para llegar a él directamente")
            assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
            job.cancel()
        }

    @Test
    fun `without a DNS from the server a public one is used`() = runTest(UnconfinedTestDispatcher()) {
        result = VpnConnectResult.Connected(FakeVpnSession(push(dns = emptyList())))
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

        assertEquals("1.1.1.1", assertNotNull(platformTunnel.config).dnsServer)
        job.cancel()
    }

    @Test
    fun `IPv4 packets from the apps go to the VPN and IPv6 ones are dropped`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

        val v4 = ipv4Packet()
        platformTunnel.tun.inject(v4)
        platformTunnel.tun.inject(ipv4Packet(version = 6))

        assertEquals(1, session().sent.size)
        assertContentEquals(v4, session().sent.single())
        job.cancel()
    }

    @Test
    fun `packets from the VPN reach the apps`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

        val reply = ipv4Packet()
        session().fromServer.trySend(reply)

        assertContentEquals(reply, platformTunnel.tun.written.single())
        job.cancel()
    }

    @Test
    fun `when the VPN ends the tunnel stops and releases everything`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }
        val session = session()

        session.fromServer.close()

        assertTrue(job.isCompleted)
        assertTrue(platformTunnel.tun.closed)
        assertTrue(session.closed)
        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `cancelling stops the tunnel and closes the VPN`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }
        val session = session()

        job.cancel()
        job.join()

        assertTrue(platformTunnel.tun.closed)
        assertTrue(session.closed)
        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    @Test
    fun `a failure opening the VPN is reported and no interface is created`() = runTest {
        result = VpnConnectResult.Failed(TunnelFailure.VPN_AUTH_FAILED)
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.VPN_AUTH_FAILED), stateStore.state.value)
        assertNull(platformTunnel.config)
    }

    @Test
    fun `if the system refuses the interface the VPN session is closed`() = runTest {
        platformTunnel.failWith = "sin permiso"
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE), stateStore.state.value)
        assertTrue(session().closed)
    }

    @Test
    fun `a platform without VPN support fails with a clear reason`() = runTest {
        val (runner, _) = newRunner(withExit = false)

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID), stateStore.state.value)
    }

    @Test
    fun `a proxy name that does not resolve fails before opening the VPN`() = runTest {
        resolvedHosts.clear()
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.PROXY_HOST_UNRESOLVED), stateStore.state.value)
        assertTrue(exitCalls.isEmpty())
    }

    @Test
    fun `a stored profile with a VPN reads its file and credentials from the secure store`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(profile, "proxypass", VpnSecrets("client\nremote x 443\n", "vpnpass"))
        val job = launch { runner.run(platformTunnel, profile.id) }

        val (_, password, vpn) = exitCalls.single()
        assertEquals("proxypass", password)
        assertEquals(VpnStart("client\nremote x 443\n", "vpnuser", "vpnpass"), vpn)
        job.cancel()
    }

    @Test
    fun `a stored profile whose VPN file is missing fails instead of connecting directly`() = runTest {
        val (runner, repository) = newRunner()
        repository.save(profile, "proxypass", vpn = null)

        runner.run(platformTunnel, profile.id)

        assertEquals(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID), stateStore.state.value)
        assertTrue(exitCalls.isEmpty())
        assertNull(platformTunnel.config, "no debe caer al modo directo en silencio")
    }

}
