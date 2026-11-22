package org.uci.proxy.connection

import kotlin.test.Test
import org.uci.proxy.core.traffic.TrafficDelta
import org.uci.proxy.core.traffic.TrafficStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.uci.proxy.core.quota.QuotaStatus
import org.uci.proxy.core.quota.QuotaTracker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.data.StoredActiveProfileStore
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeProxyTester
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.fakes.InMemoryKeyValueStore
import org.uci.proxy.fakes.MainDispatcherTest

class ConnectionViewModelTest : MainDispatcherTest() {
    private val uci = ProxyProfile("p1", "UCI", "proxy.uci.cu", 8080, ProxyProtocol.HTTP, "ana")
    private val home = ProxyProfile("p2", "Casa", "10.0.0.1", 3128, ProxyProtocol.SOCKS5, "")
    private val repository = FakeProfileRepository(listOf(uci, home)).apply { passwords["p1"] = "secreto" }
    private val storage = InMemoryKeyValueStore()
    private val tunnel = FakeTunnel()
    private val gate = CompletableDeferred<Unit>()
    private val tester = FakeProxyTester()
    private val permission = FakeLocalNetworkPermission()
    private val logs = LogBuffer()
    private val clock = TestTimeSource()
    private val traffic = TrafficStore()

    private fun viewModel(
        proxyTester: FakeProxyTester = tester,
        available: FakeTunnel = tunnel,
    ) = ConnectionViewModel(
        profiles = repository,
        activeProfileStore = StoredActiveProfileStore(storage),
        control = available,
        monitor = available,
        proxyTester = proxyTester,
        localNetworkPermission = permission,
        logBuffer = logs,
        timeSource = clock,
        traffic = traffic,
    ).also { it.uiState.keepSubscribed() }

    @Test
    fun `without a choice the first profile is active`() {
        assertEquals(uci, viewModel().uiState.value.activeProfile)
    }

    @Test
    fun `the chosen profile is remembered for the next time`() {
        viewModel().selectProfile("p2")

        assertEquals(home, viewModel().uiState.value.activeProfile)
    }

    @Test
    fun `connect uses the active profile`() {
        val vm = viewModel()
        vm.selectProfile("p2")

        vm.connect()

        assertEquals(listOf("p2"), tunnel.connectRequests)
        assertEquals(TunnelState.Connected("p2"), vm.uiState.value.tunnel)
    }

    @Test
    fun `actions use the current data even when nobody is watching the screen`() {
        // Sin suscriptor el uiState no se recalcula (WhileSubscribed): las acciones deben leer de las fuentes.
        val unwatched = ConnectionViewModel(
            repository, StoredActiveProfileStore(storage), tunnel, tunnel, tester, permission, logs, clock, traffic,
        )
        unwatched.selectProfile("p2")

        unwatched.connect()

        assertEquals(listOf("p2"), tunnel.connectRequests)
        unwatched.selectProfile("p1")
        assertEquals("p2", StoredActiveProfileStore(storage).activeProfileId.value, "conectado no se cambia de perfil")
    }

    @Test
    fun `while connected the profile cannot be changed`() {
        val vm = viewModel()
        vm.connect()

        vm.selectProfile("p2")

        assertFalse(vm.uiState.value.canChangeProfile)
        assertEquals(uci, vm.uiState.value.activeProfile)
    }

    @Test
    fun `the profile in the tunnel is shown even if another was chosen`() {
        val vm = viewModel()
        vm.selectProfile("p1")

        tunnel.emit(TunnelState.Connected("p2"))

        assertEquals(home, vm.uiState.value.activeProfile)
    }

    @Test
    fun `a failure stays visible and the profile can be changed again`() {
        val vm = viewModel()

        tunnel.emit(TunnelState.Failed(TunnelFailure.ProxyAuthFailed))

        assertEquals(TunnelState.Failed(TunnelFailure.ProxyAuthFailed), vm.uiState.value.tunnel)
        assertTrue(vm.uiState.value.canChangeProfile)
    }

    @Test
    fun `connected time is counted only after seeing the connection complete`() {
        val vm = viewModel()
        tunnel.emit(TunnelState.Connecting("p1"))
        tunnel.emit(TunnelState.Connected("p1"))
        clock += 90.seconds

        assertEquals(90.seconds, assertNotNull(vm.uiState.value.connectedSince).elapsedNow())

        tunnel.emit(TunnelState.Disconnected)
        assertNull(vm.uiState.value.connectedSince)
    }

    @Test
    fun `opening the app already connected does not invent a connected time`() {
        tunnel.emit(TunnelState.Connected("p1"))

        assertNull(viewModel().uiState.value.connectedSince)
    }

    @Test
    fun `the proxy test uses the saved password and measures how long it took`() {
        val slow = FakeProxyTester(gate = gate)
        val vm = viewModel(proxyTester = slow)

        vm.testProxy()
        assertEquals(ProxyCheck.Running, vm.uiState.value.check)
        clock += 120.milliseconds
        gate.complete(Unit)

        assertEquals(ProxyCheck.Finished(ProxyTestResult.Success, 120), vm.uiState.value.check)
        assertEquals(listOf(uci to "secreto"), slow.calls)
    }

    @Test
    fun `a failed proxy test reports the failure`() {
        tester.result = ProxyTestResult.Failure(ProxyFailure.AuthRejected)
        val vm = viewModel()

        vm.testProxy()

        assertEquals(ProxyTestResult.Failure(ProxyFailure.AuthRejected), (vm.uiState.value.check as ProxyCheck.Finished).result)
    }

    @Test
    fun `without local network permission the proxy is not tested`() {
        permission.granted = false
        val vm = viewModel()

        vm.testProxy()

        assertEquals(ProxyCheck.LocalNetworkDenied, vm.uiState.value.check)
        assertTrue(tester.calls.isEmpty())
    }

    @Test
    fun `changing profile clears the previous test result`() {
        val vm = viewModel()
        vm.testProxy()

        vm.selectProfile("p2")

        assertEquals(ProxyCheck.Idle, vm.uiState.value.check)
    }

    @Test
    fun `a test that ends after changing profile is discarded`() {
        val slow = FakeProxyTester(gate = gate)
        val vm = viewModel(proxyTester = slow)
        vm.testProxy()

        vm.selectProfile("p2")
        gate.complete(Unit)

        assertEquals(ProxyCheck.Idle, vm.uiState.value.check)
    }

    @Test
    fun `recent activity shows the latest important messages first`() {
        val vm = viewModel()
        logs.append(1, LogLevel.DEBUG, "Relay", "detalle")
        logs.append(2, LogLevel.INFO, "Tunnel", "uno")
        logs.append(3, LogLevel.WARN, "Dns", "dos")
        repeat(5) { logs.append(10L + it, LogLevel.INFO, "Relay", "más $it") }

        val recent = vm.uiState.value.recentActivity
        assertEquals(listOf("más 4", "más 3", "más 2", "más 1"), recent.map { it.message })
        assertTrue(recent.none { it.level == LogLevel.DEBUG })
    }

    @Test
    fun `without a tunnel on this platform connecting is not offered`() {
        assertFalse(viewModel(available = FakeTunnel(isAvailable = false)).uiState.value.tunnelAvailable)
    }

    @Test
    fun `the live traffic comes from the engine measurements`() {
        val vm = viewModel()
        traffic.record(TrafficDelta(millis = 1_000, up = 2_048, down = 10_240, connections = 3))

        val live = vm.uiState.value.traffic
        assertEquals(10_240, live.downRate)
        assertEquals(2_048, live.upRate)
        assertEquals(12_288, live.sessionTotal)
        assertEquals(3, live.connections)
    }

    @Test
    fun `connecting while paused by the quota means going on this month`() {
        val allowed = mutableListOf<Unit>()
        val tracker = object : QuotaTracker {
            override val status = MutableStateFlow(QuotaStatus(enabled = true, profileId = "p1", paused = true))
            override fun refresh() = Unit
            override fun allowOverLimit() {
                allowed += Unit
                status.value = status.value.copy(paused = false)
            }
        }
        val vm = ConnectionViewModel(repository, StoredActiveProfileStore(storage), tunnel, tunnel, tester, permission, logs, clock, traffic, tracker)
            .also { it.uiState.keepSubscribed() }
        assertTrue(vm.uiState.value.quota.paused)

        vm.connect()

        assertEquals(1, allowed.size)
        assertEquals(listOf("p1"), tunnel.connectRequests)
        assertFalse(vm.uiState.value.quota.paused)
    }

    @Test
    fun `no profiles means nothing to connect`() {
        val empty = ConnectionViewModel(
            FakeProfileRepository(), StoredActiveProfileStore(storage), tunnel, tunnel, tester, permission, logs, clock, traffic,
        ).also { it.uiState.keepSubscribed() }

        empty.connect()

        assertNull(empty.uiState.value.activeProfile)
        assertTrue(tunnel.connectRequests.isEmpty())
    }
}

class RoutingSummaryTest {
    private val base = ProxyProfile("p1", "UCI", "proxy.uci.cu", 8080, ProxyProtocol.HTTP, "ana")

    @Test
    fun `without rules everything goes through the proxy`() {
        assertEquals(RoutingSummary(enabled = false, defaultExit = RouteExit.PROXY, hasVpn = false), base.routingSummary())
    }

    @Test
    fun `without rules but with a VPN everything goes through the other country`() {
        val summary = base.copy(countryExit = CountryExit.OpenVpn("ca.ovpn", "u")).routingSummary()

        assertEquals(RouteExit.VPN, summary.defaultExit)
        assertTrue(summary.hasVpn)
    }

    @Test
    fun `rules are counted by exit and kind`() {
        val profile = base.copy(
            routing = RoutingRules(
                enabled = true,
                defaultExit = RouteExit.PROXY,
                rules = listOf(
                    RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN),
                    RouteRule(RuleKind.DOMAIN, "hulu.com", RouteExit.VPN),
                    RouteRule(RuleKind.APP, "com.whatsapp", RouteExit.DIRECT),
                    RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.DIRECT),
                    RouteRule(RuleKind.DOMAIN, "ads.example", RouteExit.BLOCK),
                ),
            ),
        )

        val summary = profile.routingSummary()

        assertEquals(
            mapOf(
                RouteExit.VPN to mapOf(RuleKind.DOMAIN to 2),
                RouteExit.DIRECT to mapOf(RuleKind.APP to 1, RuleKind.IP to 1),
                RouteExit.BLOCK to mapOf(RuleKind.DOMAIN to 1),
            ),
            summary.rules,
        )
    }

    @Test
    fun `disabled rules are ignored`() {
        val profile = base.copy(
            routing = RoutingRules(enabled = false, rules = listOf(RouteRule(RuleKind.DOMAIN, "x.com", RouteExit.BLOCK))),
        )

        assertTrue(profile.routingSummary().rules.isEmpty())
    }
}
