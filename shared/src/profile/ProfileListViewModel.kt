package org.uci.proxy.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.profile.ProfileRepository

class ProfileListViewModel(
    private val repository: ProfileRepository,
    private val logger: Logger,
) : ViewModel() {
    val profiles: StateFlow<List<ProxyProfile>> = repository.profiles

    private val deleteFailedState = MutableStateFlow(false)
    val deleteFailed: StateFlow<Boolean> = deleteFailedState.asStateFlow()

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Frontera con la interfaz: un fallo del almacén se muestra, no tumba la app.
                logger.log(LogLevel.ERROR, TAG, "No se pudo eliminar el perfil $id: ${e.message}")
                deleteFailedState.value = true
            }
        }
    }

    fun dismissDeleteError() {
        deleteFailedState.value = false
    }

    private companion object {
        const val TAG = "ProfileList"
    }
}
