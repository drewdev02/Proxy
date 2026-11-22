package org.uci.proxy.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.ProfileRepository

data class ProfileListUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    /** Si falló el último borrado; se muestra hasta que la persona cierra el aviso. */
    val deleteFailed: Boolean = false,
    val tunnel: TunnelUiModel = TunnelUiModel(isAvailable = false, state = TunnelState.Disconnected),
    /** «ProxyUCI en Android 16», al pie de la lista. */
    val platformLabel: String = "",
)

/**
 * El único ViewModel de la lista de perfiles. Lo que comparte con otras pantallas (los perfiles, el túnel, la
 * plataforma) lo lee de los componentes del núcleo, no de otros ViewModels, como recomienda Google.
 */
class ProfileListViewModel(
    private val repository: ProfileRepository,
    private val control: TunnelControl,
    monitor: TunnelMonitor,
    platformInfo: PlatformInfo,
    private val logger: Logger,
) : ViewModel() {
    private val deleteFailed = MutableStateFlow(false)
    private val platformLabel = "ProxyUCI en ${platformInfo.kind.displayName} ${platformInfo.osVersion}"

    val uiState: StateFlow<ProfileListUiState> = combine(repository.profiles, deleteFailed, monitor.state) { profiles, failed, tunnel ->
        ProfileListUiState(
            profiles = profiles,
            deleteFailed = failed,
            tunnel = TunnelUiModel(isAvailable = control.isAvailable, state = tunnel),
            platformLabel = platformLabel,
        )
    }.stateIn(
        viewModelScope,
        // Solo mientras alguien lo mira, y 5 s más para no reiniciar al girar la pantalla (recomendación de Google).
        SharingStarted.WhileSubscribed(5_000),
        ProfileListUiState(
            profiles = repository.profiles.value,
            tunnel = TunnelUiModel(isAvailable = control.isAvailable, state = monitor.state.value),
            platformLabel = platformLabel,
        ),
    )

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Frontera con la interfaz: un fallo del almacén se muestra, no tumba la app.
                logger.log(LogLevel.ERROR, TAG, "No se pudo eliminar el perfil $id: ${e.message}")
                deleteFailed.value = true
            }
        }
    }

    fun dismissDeleteError() {
        deleteFailed.value = false
    }

    fun connect(profileId: String) {
        viewModelScope.launch { control.connect(profileId) }
    }

    fun disconnect() {
        viewModelScope.launch { control.disconnect() }
    }

    private companion object {
        const val TAG = "ProfileList"
    }
}
