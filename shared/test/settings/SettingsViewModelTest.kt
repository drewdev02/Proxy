package org.uci.proxy.settings

import org.uci.proxy.core.settings.StoredSettingsRepository
import org.uci.proxy.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest {
    private val store = InMemoryKeyValueStore()
    private val viewModel = SettingsViewModel(StoredSettingsRepository(store))

    @Test
    fun `the connectivity switch starts on`() {
        assertTrue(viewModel.settings.value.answerConnectivityChecks)
    }

    @Test
    fun `switching it off and on is reflected and stored`() {
        viewModel.onAnswerConnectivityChecksChange(false)

        assertFalse(viewModel.settings.value.answerConnectivityChecks)
        assertFalse(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)

        viewModel.onAnswerConnectivityChecksChange(true)
        assertTrue(viewModel.settings.value.answerConnectivityChecks)
    }

    @Test
    fun `names for public IPs start on with the two factory services`() {
        val settings = viewModel.settings.value

        assertTrue(settings.nameByIp)
        assertTrue(settings.tryRawIp)
        assertEquals(listOf("sslip", "nip"), settings.nameServices.map { it.id })
    }

    @Test
    fun `the switches are reflected and stored`() {
        viewModel.onNameByIpChange(false)
        viewModel.onTryRawIpChange(false)

        assertFalse(viewModel.settings.value.nameByIp)
        assertFalse(StoredSettingsRepository(store).settings.value.tryRawIp)
    }

    @Test
    fun `a service can be switched off and moved`() {
        viewModel.onServiceEnabledChange("sslip", false)
        viewModel.onServiceMove("nip", -1)

        val services = viewModel.settings.value.nameServices
        assertEquals(listOf("nip", "sslip"), services.map { it.id })
        assertFalse(services.last().enabled)
    }

    @Test
    fun `restoring brings the services back`() {
        viewModel.onServiceMove("nip", -1)
        viewModel.onServiceEnabledChange("nip", false)

        viewModel.onResetServices()

        assertEquals(listOf("sslip", "nip"), viewModel.settings.value.nameServices.map { it.id })
        assertTrue(viewModel.settings.value.nameServices.all { it.enabled })
    }
}
