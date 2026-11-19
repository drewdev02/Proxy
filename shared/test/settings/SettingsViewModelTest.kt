package org.uci.proxy.settings

import kotlinx.coroutines.CompletableDeferred
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.proxy.NameServiceTester
import org.uci.proxy.core.settings.IpNameService
import org.uci.proxy.core.settings.IpNameServiceValidator
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.settings.ServiceError
import org.uci.proxy.core.settings.ServiceValidation
import org.uci.proxy.core.settings.StoredNameServiceStatusRepository
import org.uci.proxy.core.settings.StoredSettingsRepository
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.fakes.InMemoryKeyValueStore
import org.uci.proxy.fakes.MainDispatcherTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest : MainDispatcherTest() {
    private val store = InMemoryKeyValueStore()
    private val uci = ProxyProfile("a1", "UCI", "10.0.0.1", 3128, ProxyProtocol.HTTP, "andrey")
    private val profiles = FakeProfileRepository(listOf(uci)).apply { passwords["a1"] = "clave" }
    private val tunnel = FakeTunnel()
    private val localNetwork = FakeLocalNetworkPermission()
    private var now = 1_700_000_000_000L
    private val statusRepository = StoredNameServiceStatusRepository(store, object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    })
    private var nextId = 0

    /** Lo que responde el probador y con qué se le llamó. Con [gate] la prueba queda en curso hasta completarlo. */
    private var testResult: NameServiceTestResult = NameServiceTestResult.Works
    private var gate: CompletableDeferred<Unit>? = null
    private val testCalls = mutableListOf<Triple<IpNameService, ProxyProfile, String>>()
    private val tester = NameServiceTester { service, profile, password ->
        testCalls += Triple(service, profile, password)
        gate?.await()
        testResult
    }

    // Se crea en el primer uso, ya dentro de la prueba: viewModelScope necesita el despachador que instala la clase base.
    private val viewModel by lazy {
        SettingsViewModel(
            StoredSettingsRepository(store), profiles, tunnel, statusRepository, tester,
            IpNameServiceValidator(), { "custom-${nextId++}" }, localNetwork,
        )
    }

    @Test
    fun `the connectivity switch starts on`() {
        assertTrue(viewModel.uiState.value.settings.answerConnectivityChecks)
    }

    @Test
    fun `switching it off and on is reflected and stored`() {
        viewModel.onAnswerConnectivityChecksChange(false)

        assertFalse(viewModel.uiState.value.settings.answerConnectivityChecks)
        assertFalse(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)

        viewModel.onAnswerConnectivityChecksChange(true)
        assertTrue(viewModel.uiState.value.settings.answerConnectivityChecks)
    }

    @Test
    fun `names for public IPs start on with the two factory services`() {
        val settings = viewModel.uiState.value.settings

        assertTrue(settings.nameByIp)
        assertTrue(settings.tryRawIp)
        assertEquals(listOf("sslip", "nip"), settings.nameServices.map { it.id })
    }

    @Test
    fun `the switches are reflected and stored`() {
        viewModel.onNameByIpChange(false)
        viewModel.onTryRawIpChange(false)

        assertFalse(viewModel.uiState.value.settings.nameByIp)
        assertFalse(StoredSettingsRepository(store).settings.value.tryRawIp)
    }

    @Test
    fun `a service can be switched off and moved`() {
        viewModel.onServiceEnabledChange("sslip", false)
        viewModel.onServiceMove("nip", -1)

        val services = viewModel.uiState.value.settings.nameServices
        assertEquals(listOf("nip", "sslip"), services.map { it.id })
        assertFalse(services.last().enabled)
    }

    @Test
    fun `restoring brings the services back`() {
        viewModel.onServiceMove("nip", -1)
        viewModel.onServiceEnabledChange("nip", false)

        viewModel.onResetServices()

        assertEquals(listOf("sslip", "nip"), viewModel.uiState.value.settings.nameServices.map { it.id })
        assertTrue(viewModel.uiState.value.settings.nameServices.all { it.enabled })
    }

    @Test
    fun `a valid service is added at the end and reported as valid`() {
        val result = viewModel.addService("Mío", "{ip-}.mio.com")

        assertTrue(result is ServiceValidation.Valid)
        assertEquals(listOf("sslip", "nip", "custom-0"), viewModel.uiState.value.settings.nameServices.map { it.id })
    }

    @Test
    fun `an invalid service is not added and the errors come back for the form`() {
        val result = viewModel.addService("", "fijo.mio.com")

        assertEquals(setOf(ServiceError.NAME_EMPTY, ServiceError.NO_PLACEHOLDER), (result as ServiceValidation.Invalid).errors)
        assertEquals(2, viewModel.uiState.value.settings.nameServices.size)
    }

    @Test
    fun `the same template cannot be added twice`() {
        viewModel.addService("Mío", "{ip-}.mio.com")

        val again = viewModel.addService("Otra vez", "{IP-}.MIO.com")

        assertEquals(setOf(ServiceError.DUPLICATE), (again as ServiceValidation.Invalid).errors)
    }

    @Test
    fun `a custom service can be removed and its test result goes with it`() {
        viewModel.addService("Mío", "{ip-}.mio.com")
        viewModel.testService("custom-0")

        viewModel.removeService("custom-0")

        assertEquals(2, viewModel.uiState.value.settings.nameServices.size)
        assertEquals(null, viewModel.uiState.value.tests["custom-0"])
    }

    @Test
    fun `a factory service cannot be removed`() {
        viewModel.removeService("sslip")

        assertEquals(listOf("sslip", "nip"), viewModel.uiState.value.settings.nameServices.map { it.id })
    }

    @Test
    fun `testing asks the proxy of the first profile with its password and records that it works`() {
        viewModel.testService("sslip")

        assertEquals(ServiceTestUi.Done(NameServiceTestResult.Works), viewModel.uiState.value.tests["sslip"])
        val (service, profile, password) = testCalls.single()
        assertEquals("sslip", service.id)
        assertEquals(uci, profile)
        assertEquals("clave", password)
        assertEquals(NameOutcome.WORKED, viewModel.uiState.value.statuses["sslip"]?.outcome)
    }

    @Test
    fun `testing prefers the profile the tunnel is using`() = runTest {
        val other = uci.copy(id = "b2", name = "Casa", host = "10.0.0.9")
        val two = FakeProfileRepository(listOf(uci, other))
        val model = SettingsViewModel(
            StoredSettingsRepository(store), two, tunnel, statusRepository, tester, IpNameServiceValidator(), { "x" }, localNetwork,
        )
        tunnel.connect("b2")

        model.testService("sslip")

        assertEquals("b2", testCalls.single().second.id)
    }

    @Test
    fun `a refusal is recorded as the service's status`() {
        testResult = NameServiceTestResult.Refused("HTTP/1.1 403 Forbidden")

        viewModel.testService("nip")

        assertEquals(ServiceTestUi.Done(testResult), viewModel.uiState.value.tests["nip"])
        assertEquals(NameOutcome.REFUSED, viewModel.uiState.value.statuses["nip"]?.outcome)
    }

    @Test
    fun `a network failure or rejected credentials are shown but not recorded as the service's status`() {
        testResult = NameServiceTestResult.Unreachable("sin ruta")
        viewModel.testService("sslip")
        testResult = NameServiceTestResult.ProxyAuth
        viewModel.testService("nip")

        assertTrue(viewModel.uiState.value.statuses.isEmpty())
        assertEquals(ServiceTestUi.Done(NameServiceTestResult.ProxyAuth), viewModel.uiState.value.tests["nip"])
    }

    @Test
    fun `without any profile there is nothing to test with`() {
        val model = SettingsViewModel(
            StoredSettingsRepository(store), FakeProfileRepository(), tunnel, statusRepository, tester, IpNameServiceValidator(), { "x" }, localNetwork,
        )

        model.testService("sslip")

        assertEquals(ServiceTestUi.NoProfile, model.uiState.value.tests["sslip"])
        assertTrue(testCalls.isEmpty())
    }

    @Test
    fun `without local network access the test does not run`() {
        localNetwork.granted = false

        viewModel.testService("sslip")

        assertEquals(ServiceTestUi.LocalNetworkDenied, viewModel.uiState.value.tests["sslip"])
        assertTrue(testCalls.isEmpty())
    }

    @Test
    fun `a test already running is not started twice`() {
        gate = CompletableDeferred()

        viewModel.testService("sslip")
        viewModel.testService("sslip")

        assertEquals(ServiceTestUi.Running, viewModel.uiState.value.tests["sslip"])
        assertEquals(1, testCalls.size)
        gate!!.complete(Unit)
        assertEquals(ServiceTestUi.Done(NameServiceTestResult.Works), viewModel.uiState.value.tests["sslip"])
    }

    @Test
    fun `testing a service that does not exist does nothing`() {
        viewModel.testService("no-existe")

        assertTrue(viewModel.uiState.value.tests.isEmpty())
    }
}

class TimeAgoTest {
    private val now = 10_000_000_000L

    @Test
    fun `recent moments read as seconds`() {
        assertEquals("hace unos segundos", timeAgo(now - 5_000, now))
        assertEquals("hace unos segundos", timeAgo(now + 5_000, now), "el futuro cuenta como ahora")
    }

    @Test
    fun `minutes hours and days`() {
        assertEquals("hace 1 min", timeAgo(now - 50_000, now))
        assertEquals("hace 5 min", timeAgo(now - 5 * 60_000, now))
        assertEquals("hace 59 min", timeAgo(now - 59 * 60_000, now))
        assertEquals("hace 1 h", timeAgo(now - 3_600_000, now))
        assertEquals("hace 23 h", timeAgo(now - 23 * 3_600_000, now))
        assertEquals("hace 1 d", timeAgo(now - 86_400_000, now))
        assertEquals("hace 3 d", timeAgo(now - 3 * 86_400_000, now))
    }
}
