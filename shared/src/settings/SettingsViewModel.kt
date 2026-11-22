package org.uci.proxy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.IdGenerator
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.proxy.NameServiceTester
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameServiceValidator
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.settings.NameServiceStatus
import org.uci.proxy.core.settings.NameServiceStatusRepository
import org.uci.proxy.core.settings.ServiceValidation
import org.uci.proxy.core.settings.SettingsRepository

/** Lo que muestra la fila de un servicio tras pulsar «Probar». */
sealed interface ServiceTestUi {
    data object Running : ServiceTestUi

    data class Done(val result: NameServiceTestResult) : ServiceTestUi

    /** No hay ningún perfil con el que probar. */
    data object NoProfile : ServiceTestUi

    /** La persona no concedió el acceso a la red local, así que la prueba no llegó a hacerse. */
    data object LocalNetworkDenied : ServiceTestUi
}

/** Todo lo que muestra Ajustes, en un solo estado. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** El último resultado de cada servicio de nombres, por id. */
    val statuses: Map<String, NameServiceStatus> = emptyMap(),
    /** El resultado de la última prueba de cada servicio, por id. */
    val tests: Map<String, ServiceTestUi> = emptyMap(),
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val profiles: ProfileRepository,
    private val tunnel: TunnelMonitor,
    private val statusRepository: NameServiceStatusRepository,
    private val tester: NameServiceTester,
    private val validator: IpNameServiceValidator,
    private val idGenerator: IdGenerator,
    private val localNetworkPermission: LocalNetworkPermission,
) : ViewModel() {
    private val mutableTests = MutableStateFlow<Map<String, ServiceTestUi>>(emptyMap())

    val uiState: StateFlow<SettingsUiState> =
        combine(repository.settings, statusRepository.statuses, mutableTests, ::SettingsUiState)
            .stateIn(
                viewModelScope,
                // Solo mientras alguien lo mira, y 5 s más para no reiniciar al girar la pantalla (recomendación de Google).
                SharingStarted.WhileSubscribed(5_000),
                SettingsUiState(repository.settings.value, statusRepository.statuses.value, mutableTests.value),
            )

    /** Los ajustes tal como están guardados: la fuente de verdad para validar y probar. */
    private val settings: AppSettings get() = repository.settings.value

    fun setAnswerConnectivityChecks(enabled: Boolean) = repository.setAnswerConnectivityChecks(enabled)

    fun setIntranetDirect(enabled: Boolean) = repository.setIntranetDirect(enabled)

    fun setVpnFallbackToProxy(enabled: Boolean) = repository.setVpnFallbackToProxy(enabled)

    fun setNameByIp(enabled: Boolean) = repository.setNameByIp(enabled)

    fun setTryRawIp(enabled: Boolean) = repository.setTryRawIp(enabled)

    /** Avisar (o no) al llegar al [threshold] % de la cuota de la UCI. */
    fun setQuotaAlert(threshold: Int, enabled: Boolean) = repository.updateQuota { quota ->
        quota.copy(alerts = if (enabled) (quota.alerts + threshold).distinct().sorted() else quota.alerts - threshold)
    }

    fun setQuotaCheckMinutes(minutes: Int) = repository.updateQuota { it.copy(checkMinutes = minutes) }

    fun setQuotaPause(enabled: Boolean) = repository.updateQuota { it.copy(pauseAtLimit = enabled) }

    fun setQuotaVerifyCertificate(enabled: Boolean) = repository.updateQuota { it.copy(verifyCertificate = enabled) }

    /** Solo en modo desarrollo: otra dirección para el servicio (el simulador). Vacío vuelve al de la UCI. */
    fun setQuotaServiceUrl(url: String) = repository.updateQuota { it.copy(serviceUrl = url.trim().ifEmpty { null }) }

    fun setServiceEnabled(id: String, enabled: Boolean) = repository.setNameServiceEnabled(id, enabled)

    /** [delta] negativo sube el servicio en la lista, positivo lo baja. */
    fun moveService(id: String, delta: Int) = repository.moveNameService(id, delta)

    fun resetServices() {
        repository.resetNameServices()
        mutableTests.value = emptyMap()
    }

    /**
     * Valida y, si está bien, añade un servicio propio. Devuelve el resultado para que el formulario muestre los errores.
     */
    fun addService(name: String, template: String): ServiceValidation {
        val result = validator.validate(idGenerator.newId(), name, template, settings.nameServices)
        if (result is ServiceValidation.Valid) repository.addNameService(result.service)
        return result
    }

    fun removeService(id: String) {
        repository.removeNameService(id)
        mutableTests.update { it - id }
    }

    /**
     * Prueba si el proxy acepta los nombres del servicio. Se usa el perfil con el túnel activo y, si no hay, el primero de
     * la lista. Si el túnel está activo y el proxy se llama por nombre, el DNS del túnel puede impedir alcanzarlo: entonces
     * hay que desconectar y probar de nuevo.
     */
    fun testService(id: String) {
        val service = settings.nameServices.firstOrNull { it.id == id } ?: return
        if (mutableTests.value[id] == ServiceTestUi.Running) return
        val profile = profileForTest()
        if (profile == null) {
            setTest(id, ServiceTestUi.NoProfile)
            return
        }
        setTest(id, ServiceTestUi.Running)
        viewModelScope.launch {
            val outcome = try {
                if (!localNetworkPermission.request()) {
                    ServiceTestUi.LocalNetworkDenied
                } else {
                    val result = tester.test(service, profile, profiles.password(profile.id))
                    // Lo que dice el proxy sobre el nombre vale como dato del servicio; un fallo de red o de credenciales, no.
                    when (result) {
                        NameServiceTestResult.Works -> statusRepository.report(id, NameOutcome.WORKED)
                        is NameServiceTestResult.Refused -> statusRepository.report(id, NameOutcome.REFUSED)
                        else -> Unit
                    }
                    ServiceTestUi.Done(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceTestUi.Done(NameServiceTestResult.Unreachable(e.message.orEmpty()))
            }
            setTest(id, outcome)
        }
    }

    private fun profileForTest(): ProxyProfile? {
        val state = tunnel.state.value
        val activeId = (state as? TunnelState.Connected)?.profileId ?: (state as? TunnelState.Connecting)?.profileId
        val all = profiles.profiles.value
        return all.firstOrNull { it.id == activeId } ?: all.firstOrNull()
    }

    private fun setTest(id: String, ui: ServiceTestUi) = mutableTests.update { it + (id to ui) }
}
