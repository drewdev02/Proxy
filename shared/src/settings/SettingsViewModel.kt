package org.uci.proxy.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.SettingsRepository

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings

    fun onAnswerConnectivityChecksChange(enabled: Boolean) = repository.setAnswerConnectivityChecks(enabled)

    fun onNameByIpChange(enabled: Boolean) = repository.setNameByIp(enabled)

    fun onTryRawIpChange(enabled: Boolean) = repository.setTryRawIp(enabled)

    fun onServiceEnabledChange(id: String, enabled: Boolean) = repository.setNameServiceEnabled(id, enabled)

    /** [delta] negativo sube el servicio en la lista, positivo lo baja. */
    fun onServiceMove(id: String, delta: Int) = repository.moveNameService(id, delta)

    fun onResetServices() = repository.resetNameServices()
}
