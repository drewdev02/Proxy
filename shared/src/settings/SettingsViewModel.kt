package org.uci.proxy.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.SettingsRepository

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    fun onAnswerConnectivityChecksChange(enabled: Boolean) = repository.setAnswerConnectivityChecks(enabled)
}
