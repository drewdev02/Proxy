package org.uci.proxy.quota

import org.uci.proxy.core.data.StoredQuotaStore
import org.uci.proxy.core.model.UciQuota
import org.uci.proxy.core.port.QuotaFetch
import org.uci.proxy.core.port.UciQuotaService
import org.uci.proxy.core.quota.QuotaMonitor
import org.uci.proxy.core.quota.QuotaProblem
import org.uci.proxy.core.quota.QuotaSoap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.data.StoredActiveProfileStore
import org.uci.proxy.core.data.StoredSettingsRepository
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.fakes.InMemoryKeyValueStore
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaMonitorTest {
    private val uci = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "UCI\\ana")
    private val home = ProxyProfile("p2", "Casa", "10.0.0.1", 3128, ProxyProtocol.HTTP, "ana")

    private class FakeService : UciQuotaService {
        val calls = mutableListOf<Triple<String, String, String>>()
        var verify = mutableListOf<Boolean>()
        var next: () -> QuotaFetch = { QuotaFetch.Success(2000, 100.0, "Profesores") }

        override suspend fun fetch(account: String, password: String, url: String, verifyCertificate: Boolean): QuotaFetch {
            calls += Triple(account, password, url)
            verify += verifyCertificate
            return next()
        }
    }

    private val service = FakeService()
    private val notifications = mutableListOf<Pair<String, String>>()
    private val keyValues = InMemoryKeyValueStore()
    private val settings = StoredSettingsRepository(InMemoryKeyValueStore())
    private val tunnel = FakeTunnel()
    private val profiles = FakeProfileRepository(listOf(uci, home)).apply { passwords["p1"] = "clave1"; passwords["p2"] = "clave2" }
    private val active = StoredActiveProfileStore(InMemoryKeyValueStore())

    private fun TestScope.monitor(): QuotaMonitor {
        // 22 de noviembre de 2026 a mediodía, más lo que avance el reloj de la prueba.
        val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_795_348_800_000 + testScheduler.currentTime)
        }
        return QuotaMonitor(
            profiles, active, tunnel, tunnel, settings, service, StoredQuotaStore(keyValues), { title, message -> notifications += title to message },
            clock, AppDispatchers(StandardTestDispatcher(testScheduler)), RecordingLogger(),
        )
    }

    private fun TestScope.started(): QuotaMonitor = monitor().also {
        it.start()
        runCurrent()
    }

    @Test
    fun `a uci profile is checked once on start with its account without the domain`() = runTest {
        val monitor = started()

        assertEquals(listOf(Triple("ana", "clave1", QuotaSoap.SERVICE_URL)), service.calls)
        val status = monitor.status.value
        assertTrue(status.enabled)
        assertEquals(UciQuota(2000, 100.0, "Profesores", status.quota!!.checkedAtMillis), status.quota)
        // Sin conexión no se repite.
        advanceTimeBy(30.minutes)
        assertEquals(1, service.calls.size)
        monitor.stop()
    }

    @Test
    fun `a profile that is not from the uci is not checked`() = runTest {
        active.select("p2")
        val monitor = started()

        assertTrue(service.calls.isEmpty())
        assertFalse(monitor.status.value.enabled)
        monitor.stop()
    }

    @Test
    fun `connected it is checked again at the chosen interval`() = runTest {
        settings.updateQuota { it.copy(checkMinutes = 15) }
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()
        assertEquals(1, service.calls.size)

        advanceTimeBy(15.minutes + 1.seconds)
        assertEquals(2, service.calls.size)
        advanceTimeBy(15.minutes)
        assertEquals(3, service.calls.size)
        monitor.stop()
    }

    @Test
    fun `wrong credentials stop the checks until something changes`() = runTest {
        service.next = { QuotaFetch.WrongCredentials("Usuario o contraseña incorrectos") }
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()

        advanceTimeBy(60.minutes)
        assertEquals(1, service.calls.size)
        assertEquals(QuotaProblem.WRONG_CREDENTIALS, monitor.status.value.problem)

        service.next = { QuotaFetch.Success(2000, 10.0, "x") }
        monitor.refresh()
        runCurrent()
        assertNull(monitor.status.value.problem)
        monitor.stop()
    }

    @Test
    fun `out of reach it retries sooner but keeps the last known quota`() = runTest {
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()
        service.next = { QuotaFetch.Unreachable("sin red") }

        advanceTimeBy(5.minutes + 1.seconds)
        assertEquals(2, service.calls.size)
        assertEquals(QuotaProblem.UNREACHABLE, monitor.status.value.problem)
        assertEquals(100.0, monitor.status.value.quota?.usedMb, "la última conocida sigue a la vista")
        advanceTimeBy(1.minutes + 1.seconds)
        assertEquals(3, service.calls.size, "el primer reintento, al minuto")
        monitor.stop()
    }

    @Test
    fun `each threshold warns once and again only after the use drops well below it`() = runTest {
        var used = 920.0
        service.next = { QuotaFetch.Success(1000, used, "x") }
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()
        assertEquals(listOf("Cuota de la UCI al 90 %"), notifications.map { it.first }, "solo el más alto")

        // Sigue por encima, o baja poco (al 88 %): no se repite.
        used = 880.0
        advanceTimeBy(5.minutes + 1.seconds)
        assertEquals(1, notifications.size)
        used = 930.0
        advanceTimeBy(5.minutes)
        assertEquals(1, notifications.size)

        // Baja de verdad (por debajo del 85 %) y vuelve a subir: avisa otra vez.
        used = 600.0
        advanceTimeBy(5.minutes)
        used = 910.0
        advanceTimeBy(5.minutes)
        assertEquals(2, notifications.size)
        monitor.stop()
    }

    @Test
    fun `the use can go past the quota`() = runTest {
        service.next = { QuotaFetch.Success(2000, 2300.0, "x") }
        val monitor = started()

        assertEquals(115.0, monitor.status.value.quota?.percent)
        assertEquals(0.0, monitor.status.value.quota?.remainingMb)
        assertTrue(notifications.single().second.contains("Te pasaste 300 MB"))
        monitor.stop()
    }

    @Test
    fun `at the limit with the pause on it disconnects and going on lasts until the use drops`() = runTest {
        var used = 950.0
        settings.updateQuota { it.copy(pauseAtLimit = true) }
        service.next = { QuotaFetch.Success(1000, used, "x") }
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()

        assertEquals(TunnelState.Disconnected, tunnel.state.value)
        assertTrue(monitor.status.value.paused)
        assertTrue(notifications.any { it.first == "Túnel en pausa por la cuota" })

        // «Conectar igual»: mientras siga por encima, no vuelve a pausar.
        monitor.allowOverLimit()
        assertFalse(monitor.status.value.paused)
        tunnel.emit(TunnelState.Connected("p1"))
        advanceTimeBy(5.minutes + 1.seconds)
        assertTrue(tunnel.state.value is TunnelState.Connected)

        // Baja del 85 % y vuelve a pasar del 90 %: la pausa vale otra vez.
        used = 700.0
        advanceTimeBy(5.minutes)
        used = 960.0
        advanceTimeBy(5.minutes)
        assertEquals(TunnelState.Disconnected, tunnel.state.value)
        assertTrue(monitor.status.value.paused)
        monitor.stop()
    }

    @Test
    fun `without the pause reaching the limit only warns`() = runTest {
        service.next = { QuotaFetch.Success(1000, 950.0, "x") }
        tunnel.emit(TunnelState.Connected("p1"))
        val monitor = started()

        assertTrue(tunnel.state.value is TunnelState.Connected)
        assertFalse(monitor.status.value.paused)
        monitor.stop()
    }

    @Test
    fun `the development url and the certificate choice reach the service`() = runTest {
        settings.updateQuota { it.copy(serviceUrl = "http://10.0.2.2:8443/servicios/v1/InetCuotasWS.php", verifyCertificate = false) }
        val monitor = started()

        assertEquals("http://10.0.2.2:8443/servicios/v1/InetCuotasWS.php", service.calls.single().third)
        assertEquals(listOf(false), service.verify)
        monitor.stop()
    }

    @Test
    fun `the last quota is shown at once on the next start`() = runTest {
        started().stop()
        service.next = { QuotaFetch.Unreachable("fuera de la UCI") }

        advanceTimeBy(10.minutes)
        val again = started()

        assertEquals(100.0, again.status.value.quota?.usedMb)
        assertEquals(QuotaProblem.UNREACHABLE, again.status.value.problem)
        again.stop()
    }
}
