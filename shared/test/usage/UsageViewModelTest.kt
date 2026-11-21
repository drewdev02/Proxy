package org.uci.proxy.usage

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.traffic.NamedTraffic
import org.uci.proxy.core.traffic.TrafficRecorder
import org.uci.proxy.core.traffic.TrafficStore
import org.uci.proxy.core.traffic.TrafficUsage
import org.uci.proxy.fakes.FakeTrafficHistory
import org.uci.proxy.fakes.MainDispatcherTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class UsageViewModelTest : MainDispatcherTest() {
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-11-21T15:00:00Z")
    }
    private val history = FakeTrafficHistory()
    private val recorder = TrafficRecorder(TrafficStore(), history, clock, AppDispatchers(UnconfinedTestDispatcher()), timeZone = { TimeZone.UTC })
    private val today = recorder.today()

    private val viewModel by lazy { UsageViewModel(history, recorder).also { it.uiState.keepSubscribed() } }

    private fun usage(exit: RouteExit, host: String?, down: Long, app: String? = null) = TrafficUsage(exit, host, app, up = 0, down = down)

    @Test
    fun `the week shows every day up to today the empty ones as zero`() = runTest {
        history.add(today - 2, listOf(usage(RouteExit.PROXY, "github.com", 300), usage(RouteExit.VPN, "netflix.com", 700)))
        history.add(today, listOf(usage(RouteExit.PROXY, "github.com", 100)))

        val state = viewModel.uiState.value
        assertEquals((today - 6..today).toList(), state.days.map { it.day })
        assertEquals(DayUsage(today - 2, proxy = 300, country = 700), state.days[4])
        assertEquals(0, state.days[5].total)
        assertEquals(400, state.proxy)
        assertEquals(700, state.country)
        assertEquals(1_100, state.total)
    }

    @Test
    fun `the destinations are ranked by consumption`() = runTest {
        history.add(today, listOf(usage(RouteExit.PROXY, "github.com", 100), usage(RouteExit.VPN, "netflix.com", 700), usage(RouteExit.PROXY, "github.com", 50)))

        assertEquals(listOf(NamedTraffic("netflix.com", 0, 700), NamedTraffic("github.com", 0, 150)), viewModel.uiState.value.hosts)
    }

    @Test
    fun `today leaves out the previous days`() = runTest {
        history.add(today - 1, listOf(usage(RouteExit.PROXY, "ayer.cu", 500)))
        history.add(today, listOf(usage(RouteExit.PROXY, "hoy.cu", 20)))

        viewModel.selectPeriod(UsagePeriod.TODAY)

        val state = viewModel.uiState.value
        assertEquals(listOf(today), state.days.map { it.day })
        assertEquals(20, state.total)
        assertEquals(listOf("hoy.cu"), state.hosts.map { it.name })
    }

    @Test
    fun `apps appear only where the system says which app opened each connection`() = runTest {
        history.add(today, listOf(usage(RouteExit.PROXY, "web.whatsapp.com", 80, app = "com.whatsapp"), usage(RouteExit.PROXY, "github.com", 40)))

        assertEquals(listOf("com.whatsapp"), viewModel.uiState.value.apps.map { it.name })
    }

    @Test
    fun `nothing recorded is shown as no consumption once loaded`() {
        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals(0, state.total)
        assertFalse(state.days.any { it.total > 0 })
    }
}
