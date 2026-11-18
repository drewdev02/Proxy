package org.uci.proxy.settings

import org.uci.proxy.core.settings.StoredSettingsRepository
import org.uci.proxy.fakes.InMemoryKeyValueStore
import kotlin.test.Test
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
}
