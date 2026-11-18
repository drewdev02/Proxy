package org.uci.proxy.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.profile.IdGenerator
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.ProfileValidation
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.proxy.ProxyTester

sealed interface ProxyTestUiState {
    data object Idle : ProxyTestUiState

    data object Running : ProxyTestUiState

    data class Finished(val result: ProxyTestResult) : ProxyTestUiState

    /** La persona no concedió el acceso a la red local, así que la prueba no llegó a hacerse. */
    data object LocalNetworkDenied : ProxyTestUiState
}

data class ProfileEditorUiState(
    val isNew: Boolean = true,
    val draft: ProfileDraft = ProfileDraft(),
    val password: String = "",
    val errors: Set<ProfileField> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    /** Pasa a true cuando el perfil quedó guardado; la pantalla lo usa para cerrarse. */
    val saved: Boolean = false,
    val test: ProxyTestUiState = ProxyTestUiState.Idle,
) {
    val isBusy: Boolean get() = isSaving || test == ProxyTestUiState.Running
}

class ProfileEditorViewModel(
    private val repository: ProfileRepository,
    private val validator: ProfileValidator,
    private val idGenerator: IdGenerator,
    private val proxyTester: ProxyTester,
    private val localNetworkPermission: LocalNetworkPermission,
    private val logger: Logger,
) : ViewModel() {
    private val state = MutableStateFlow(ProfileEditorUiState())
    val uiState: StateFlow<ProfileEditorUiState> = state.asStateFlow()

    private var editingId: String? = null

    /** Para qué perfil se preparó el formulario (null dentro de [Started] es «perfil nuevo»); null si aún no se preparó. */
    private var started: Started? = null

    private data class Started(val profileId: String?)

    /**
     * Prepara el formulario: vacío si [profileId] es null, o con los datos del perfil a editar.
     *
     * Pedir lo mismo otra vez no hace nada. La pantalla lo pide al aparecer, y vuelve a aparecer cuando el sistema recrea
     * la actividad (al rotar el móvil, por ejemplo) mientras este view model sigue vivo con lo que el usuario escribió.
     */
    fun start(profileId: String?) {
        if (started == Started(profileId)) return
        started = Started(profileId)
        val existing = profileId?.let { id -> repository.profiles.value.firstOrNull { it.id == id } }
        editingId = existing?.id
        state.value = ProfileEditorUiState(
            isNew = existing == null,
            draft = existing?.toDraft() ?: ProfileDraft(),
        )
        if (existing != null) {
            viewModelScope.launch {
                val password = guarded("leer la contraseña") { repository.password(existing.id) }.orEmpty()
                // Si entretanto se abrió otro perfil, esta contraseña ya no corresponde al formulario.
                if (editingId == existing.id) state.update { it.copy(password = password) }
            }
        }
    }

    fun onDraftChange(draft: ProfileDraft) {
        // Al corregir un campo se retira su error; el resto se revalida al guardar.
        // El resultado de una prueba anterior deja de valer en cuanto cambian los datos.
        state.update { current ->
            current.copy(
                draft = draft,
                errors = current.errors - changedFields(current.draft, draft),
                saveFailed = false,
                test = current.test.afterEdit(),
            )
        }
    }

    fun onPasswordChange(password: String) {
        state.update { it.copy(password = password, saveFailed = false, test = it.test.afterEdit()) }
    }

    /**
     * La pantalla ya reaccionó al guardado y se cerró. Sin esto, [ProfileEditorUiState.saved] seguiría a true y, si el
     * sistema recreara la actividad antes de que este view model se descarte, la pantalla volvería a cerrarse.
     */
    fun onSavedHandled() {
        state.update { it.copy(saved = false) }
    }

    /** Prueba el proxy con lo que hay en el formulario, sin necesidad de guardarlo antes. */
    fun testProxy() {
        val current = state.value
        if (current.isBusy) return

        when (val validation = validator.validate(editingId ?: TEST_PROFILE_ID, current.draft)) {
            is ProfileValidation.Invalid -> state.update { it.copy(errors = validation.errors) }
            is ProfileValidation.Valid -> {
                state.update { it.copy(errors = emptySet(), test = ProxyTestUiState.Running) }
                viewModelScope.launch {
                    val outcome = if (localNetworkPermission.request()) {
                        ProxyTestUiState.Finished(proxyTester.test(validation.profile, current.password))
                    } else {
                        ProxyTestUiState.LocalNetworkDenied
                    }
                    // Si el formulario cambió mientras tanto, este resultado ya no le corresponde.
                    state.update { if (it.test == ProxyTestUiState.Running) it.copy(test = outcome) else it }
                }
            }
        }
    }

    fun save() {
        val current = state.value
        if (current.isBusy) return

        when (val validation = validator.validate(editingId ?: idGenerator.newId(), current.draft)) {
            is ProfileValidation.Invalid -> state.update { it.copy(errors = validation.errors) }
            is ProfileValidation.Valid -> {
                state.update { it.copy(errors = emptySet(), isSaving = true, saveFailed = false) }
                viewModelScope.launch {
                    val ok = guarded("guardar el perfil") { repository.save(validation.profile, current.password) } != null
                    state.update { it.copy(isSaving = false, saved = ok, saveFailed = !ok) }
                }
            }
        }
    }

    /** Frontera con la interfaz: un fallo del almacén se registra y se muestra, no tumba la app. */
    private suspend fun <T> guarded(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log(LogLevel.ERROR, TAG, "No se pudo $action: ${e.message}")
        null
    }

    private fun changedFields(old: ProfileDraft, new: ProfileDraft): Set<ProfileField> = buildSet {
        if (old.name != new.name) add(ProfileField.NAME)
        if (old.host != new.host) add(ProfileField.HOST)
        if (old.port != new.port) add(ProfileField.PORT)
    }

    private fun ProxyProfile.toDraft() = ProfileDraft(
        name = name,
        host = host,
        port = port.toString(),
        protocol = protocol,
        username = username,
    )

    private fun ProxyTestUiState.afterEdit(): ProxyTestUiState =
        if (this == ProxyTestUiState.Running) this else ProxyTestUiState.Idle

    private companion object {
        const val TAG = "ProfileEditor"

        // La prueba no guarda nada: el perfil temporal solo necesita un id cualquiera.
        const val TEST_PROFILE_ID = "prueba"
    }
}
