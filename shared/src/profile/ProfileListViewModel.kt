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
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.common.StorageFailure
import org.uci.proxy.common.classifyStorageError

data class ProfileListUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    /** Por qué falló el último borrado; se muestra hasta que la persona cierra el aviso. */
    val deleteFailure: StorageFailure? = null,
    val tunnel: TunnelUiModel = TunnelUiModel(isAvailable = false, state = TunnelState.Disconnected),
    /** La plataforma («Android», «iOS»…; es un nombre propio, no se traduce), para el pie de la lista. */
    val platformName: String = "",
    /** La versión del sistema, tal como la da la plataforma. */
    val osVersion: String = "",
) {
    val deleteFailed: Boolean get() = deleteFailure != null
}

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
    private val deleteFailure = MutableStateFlow<StorageFailure?>(null)
    private val platformName = platformInfo.kind.displayName
    private val osVersion = platformInfo.osVersion

    val uiState: StateFlow<ProfileListUiState> = combine(repository.profiles, deleteFailure, monitor.state) { profiles, failure, tunnel ->
        ProfileListUiState(
            profiles = profiles,
            deleteFailure = failure,
            tunnel = TunnelUiModel(isAvailable = control.isAvailable, state = tunnel),
            platformName = platformName,
            osVersion = osVersion,
        )
    }.stateIn(
        viewModelScope,
        // Solo mientras alguien lo mira, y 5 s más para no reiniciar al girar la pantalla (recomendación de Google).
        SharingStarted.WhileSubscribed(5_000),
        ProfileListUiState(
            profiles = repository.profiles.value,
            tunnel = TunnelUiModel(isAvailable = control.isAvailable, state = monitor.state.value),
            platformName = platformName,
            osVersion = osVersion,
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
                deleteFailure.value = classifyStorageError(e)
            }
        }
    }

    fun dismissDeleteError() {
        deleteFailure.value = null
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
