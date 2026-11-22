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
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.fakes.fakeEngines
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnNetwork
import org.uci.proxy.core.vpn.VpnSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun push(dns: List<String> = listOf("10.98.0.1")) = VpnNetwork(address = "10.98.0.9", prefixLength = 16, dnsServers = dns, mtu = 1500)

/** Una sesión VPN en memoria: lo que la prueba meta en [fromServer] llega a la interfaz, y lo que la interfaz envíe queda en [sent]. */
private class FakeVpnSession(override val network: VpnNetwork = push()) : VpnSession {
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
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey", CountryExit.OpenVpn("pais.ovpn", "vpnuser"))
    private val start = CountryExitStart(CountryExit.OpenVpn("pais.ovpn", "vpnuser"), CountryExitSecrets.OpenVpn("client\n", "vpnpass"))

    private var result: VpnConnectResult = VpnConnectResult.Connected(FakeVpnSession())
    private val exitCalls = mutableListOf<Triple<ProxyProfile, String, CountryExitStart>>()
    private var exitSettings: AppSettings? = null
    private val exit = fakeEngines { context, start ->
        exitCalls += Triple(context.proxy, context.proxyPassword, start)
        exitSettings = context.settings
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
            engines = if (withExit) exit else CountryEngines.None,
        )
        return runner to repository
    }

    private fun session() = (result as VpnConnectResult.Connected).session as FakeVpnSession

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
    fun `the settings reach the VPN exit so it knows how to name public IPs`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val settings = AppSettings(nameByIp = false, tryRawIp = false)
        val job = launch { runner.run(platformTunnel, profile, "x", settings = settings, vpn = start) }

        assertEquals(settings, exitSettings)
        job.cancel()
    }

    @Test
    fun `the interface keeps its own address and adds the network of the VPN`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

        val config = assertNotNull(platformTunnel.config)
        assertNotEquals("10.98.0.9", config.address, "las apps salen de la interfaz y el reparto pone la dirección de la VPN")
        assertEquals(1500, config.mtu)
        assertEquals("10.8.0.20", config.proxyAddress)
        fun routed(address: String) = config.routes.any { it.contains(parseIpv4(address)!!) }
        assertTrue(routed("10.98.0.1"), "el DNS de la VPN vive en su red")
        assertTrue(routed("8.8.8.8"))
        assertFalse(routed("10.8.0.1"), "la red del proxy queda fuera, para llegar a él directamente")
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        job.cancel()
    }

    @Test
    fun `the interface does not take a larger MTU than the VPN`() = runTest(UnconfinedTestDispatcher()) {
        result = VpnConnectResult.Connected(FakeVpnSession(push().copy(mtu = 1280)))
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }

        assertEquals(1280, assertNotNull(platformTunnel.config).mtu)
        job.cancel()
    }

    @Test
    fun `when the VPN ends the tunnel stays up and says Otro País is down`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, _) = newRunner()
        val job = launch { runner.run(platformTunnel, profile, "x", vpn = start) }
        val session = session()

        session.fromServer.close()

        assertTrue(job.isActive)
        assertFalse(platformTunnel.tun.closed)
        assertTrue(session.closed)
        assertEquals(TunnelState.Connected("p1", vpnDown = true), stateStore.state.value)
        job.cancel()
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
    fun `bad VPN credentials are reported and no interface is created`() = runTest {
        result = VpnConnectResult.Failed(TunnelFailure.VpnAuthFailed)
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.VpnAuthFailed), stateStore.state.value)
        assertNull(platformTunnel.config)
        assertEquals(1, exitCalls.size, "no se reintenta")
    }

    @Test
    fun `if the system refuses the interface the VPN session is closed`() = runTest {
        platformTunnel.failWith = "sin permiso"
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.InterfaceUnavailable), stateStore.state.value)
        assertTrue(session().closed)
    }

    @Test
    fun `a platform without VPN support fails with a clear reason`() = runTest {
        val (runner, _) = newRunner(withExit = false)

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.EngineUnavailable), stateStore.state.value)
    }

    @Test
    fun `a proxy name that does not resolve fails before opening the VPN`() = runTest {
        resolvedHosts.clear()
        val (runner, _) = newRunner()

        runner.run(platformTunnel, profile, "x", vpn = start)

        assertEquals(TunnelState.Failed(TunnelFailure.ProxyHostUnresolved(profile.host)), stateStore.state.value)
        assertTrue(exitCalls.isEmpty())
    }

    @Test
    fun `a stored profile with a VPN reads its file and credentials from the secure store`() = runTest(UnconfinedTestDispatcher()) {
        val (runner, repository) = newRunner()
        repository.save(profile, "proxypass", CountryExitSecrets.OpenVpn("client\nremote x 443\n", "vpnpass"))
        val job = launch { runner.run(platformTunnel, profile.id) }

        val (_, password, vpn) = exitCalls.single()
        assertEquals("proxypass", password)
        assertEquals(CountryExitStart(profile.countryExit!!, CountryExitSecrets.OpenVpn("client\nremote x 443\n", "vpnpass")), vpn)
        job.cancel()
    }

    @Test
    fun `a stored profile whose VPN file is missing fails instead of connecting directly`() = runTest {
        val (runner, repository) = newRunner()
        repository.save(profile, "proxypass", exitSecrets = null)

        runner.run(platformTunnel, profile.id)

        assertEquals(TunnelState.Failed(TunnelFailure.VpnSecretsMissing), stateStore.state.value)
        assertTrue(exitCalls.isEmpty())
        assertNull(platformTunnel.config, "no debe caer al modo directo en silencio")
    }

}
