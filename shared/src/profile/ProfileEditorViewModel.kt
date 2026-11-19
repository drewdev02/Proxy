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
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.routing.parseRule
import org.uci.proxy.core.model.VpnProfile
import org.uci.proxy.core.model.VpnSecrets
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
import org.uci.proxy.core.vpn.OvpnConfigException
import org.uci.proxy.core.vpn.parseOvpn

sealed interface ProxyTestUiState {
    data object Idle : ProxyTestUiState

    data object Running : ProxyTestUiState

    data class Finished(val result: ProxyTestResult) : ProxyTestUiState

    /** La persona no concedió el acceso a la red local, así que la prueba no llegó a hacerse. */
    data object LocalNetworkDenied : ProxyTestUiState
}

/** La VPN del perfil («Otro País») tal como se edita. Con [enabled] en false el perfil sale directo por el proxy. */
data class VpnDraft(
    val enabled: Boolean = false,
    val fileName: String = "",
    /** El contenido del archivo ya leído; vacío si no se eligió uno válido. */
    val ovpn: String = "",
    val username: String = "",
    val password: String = "",
    /** Por qué no sirve el archivo elegido, para mostrarlo. */
    val fileError: String? = null,
    /** Si el servidor VPN pide usuario y contraseña (casi todos los comerciales sí). */
    val needsCredentials: Boolean = true,
)

/** Las reglas de salida tal como se editan, más la regla que se está escribiendo. */
data class RoutingDraft(
    val rules: RoutingRules = RoutingRules(),
    val newRule: String = "",
    val newRuleExit: RouteExit = RouteExit.VPN,
    /** Lo escrito no es ni un dominio ni una IP. */
    val newRuleInvalid: Boolean = false,
)

/** Lo que la sección de reglas de salida puede pedir. */
interface RoutingActions {
    fun onRoutingEnabledChange(enabled: Boolean)

    fun onDefaultExitChange(exit: RouteExit)

    fun onNewRuleChange(text: String)

    fun onNewRuleExitChange(exit: RouteExit)

    fun addRule()

    /** Añade una regla para la app [packageName], con la salida elegida para la regla nueva. */
    fun addAppRule(packageName: String)

    fun onRuleExitChange(index: Int, exit: RouteExit)

    /** Sube ([delta] negativo) o baja una regla. */
    fun moveRule(index: Int, delta: Int)

    fun removeRule(index: Int)

    /** Para vistas previas. */
    object None : RoutingActions {
        override fun onRoutingEnabledChange(enabled: Boolean) = Unit
        override fun onDefaultExitChange(exit: RouteExit) = Unit
        override fun onNewRuleChange(text: String) = Unit
        override fun onNewRuleExitChange(exit: RouteExit) = Unit
        override fun addRule() = Unit
        override fun addAppRule(packageName: String) = Unit
        override fun onRuleExitChange(index: Int, exit: RouteExit) = Unit
        override fun moveRule(index: Int, delta: Int) = Unit
        override fun removeRule(index: Int) = Unit
    }
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
    val vpn: VpnDraft = VpnDraft(),
    val routing: RoutingDraft = RoutingDraft(),
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
) : ViewModel(), RoutingActions {
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
            vpn = existing?.vpn?.let { VpnDraft(enabled = true, fileName = it.fileName, username = it.username) } ?: VpnDraft(),
            routing = RoutingDraft(rules = existing?.routing ?: RoutingRules()),
        )
        if (existing != null) {
            viewModelScope.launch {
                val password = guarded("leer la contraseña") { repository.password(existing.id) }.orEmpty()
                val secrets = if (existing.vpn != null) guarded("leer la VPN") { repository.vpnSecrets(existing.id) } else null
                // Si entretanto se abrió otro perfil, estos datos ya no corresponden al formulario.
                if (editingId == existing.id) {
                    state.update {
                        it.copy(
                            password = password,
                            vpn = if (secrets == null) it.vpn else it.vpn.copy(
                                ovpn = secrets.ovpn,
                                password = secrets.password,
                                needsCredentials = needsCredentials(secrets.ovpn),
                            ),
                        )
                    }
                }
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

    fun onVpnEnabledChange(enabled: Boolean) {
        state.update { it.copy(vpn = it.vpn.copy(enabled = enabled), errors = it.errors - VPN_FIELDS, saveFailed = false) }
    }

    /** La persona eligió un archivo `.ovpn`: se comprueba enseguida, para avisar antes de guardar y no al conectar. */
    fun onVpnFileChosen(fileName: String, text: String) {
        val vpn = try {
            val config = parseOvpn(text)
            state.value.vpn.copy(
                enabled = true,
                fileName = fileName,
                ovpn = text,
                fileError = null,
                needsCredentials = config.needsCredentials,
            )
        } catch (e: OvpnConfigException) {
            state.value.vpn.copy(fileName = fileName, ovpn = "", fileError = e.message)
        }
        state.update { it.copy(vpn = vpn, errors = it.errors - ProfileField.VPN_FILE, saveFailed = false) }
    }

    fun onVpnUsernameChange(username: String) {
        state.update { it.copy(vpn = it.vpn.copy(username = username), errors = it.errors - ProfileField.VPN_USERNAME, saveFailed = false) }
    }

    fun onVpnPasswordChange(password: String) {
        state.update { it.copy(vpn = it.vpn.copy(password = password), saveFailed = false) }
    }

    override fun onRoutingEnabledChange(enabled: Boolean) = updateRules { it.copy(enabled = enabled) }

    override fun onDefaultExitChange(exit: RouteExit) = updateRules { it.copy(defaultExit = exit) }

    override fun onNewRuleChange(text: String) {
        state.update { it.copy(routing = it.routing.copy(newRule = text, newRuleInvalid = false)) }
    }

    override fun onNewRuleExitChange(exit: RouteExit) {
        state.update { it.copy(routing = it.routing.copy(newRuleExit = exit)) }
    }

    /** Añade lo escrito al final de la lista. Si es un dominio con salida «Directo», que no existe para dominios, pasa a «Proxy». */
    override fun addRule() {
        val routing = state.value.routing
        val parsed = parseRule(routing.newRule, routing.newRuleExit)
        if (parsed == null) {
            state.update { it.copy(routing = it.routing.copy(newRuleInvalid = true)) }
            return
        }
        val rule = if (parsed.exit in RoutingRules.exitsFor(parsed.kind)) parsed else parsed.copy(exit = RouteExit.PROXY)
        appendRule(rule, clearText = true)
    }

    override fun addAppRule(packageName: String) {
        if (packageName.isBlank()) return
        appendRule(RouteRule(RuleKind.APP, packageName, state.value.routing.newRuleExit), clearText = false)
    }

    /** Al final de la lista; si ya había una para lo mismo, se sustituye. */
    private fun appendRule(rule: RouteRule, clearText: Boolean) {
        state.update {
            it.copy(
                routing = it.routing.copy(
                    rules = it.routing.rules.copy(rules = it.routing.rules.rules.filterNot { r -> r.kind == rule.kind && r.value == rule.value } + rule),
                    newRule = if (clearText) "" else it.routing.newRule,
                    newRuleInvalid = if (clearText) false else it.routing.newRuleInvalid,
                ),
                saveFailed = false,
            )
        }
    }

    override fun onRuleExitChange(index: Int, exit: RouteExit) = updateRules { rules ->
        rules.copy(rules = rules.rules.mapIndexed { i, rule -> if (i == index) rule.copy(exit = exit) else rule })
    }

    override fun moveRule(index: Int, delta: Int) = updateRules { rules ->
        val target = index + delta
        if (index !in rules.rules.indices || target !in rules.rules.indices) return@updateRules rules
        val list = rules.rules.toMutableList()
        list.add(target, list.removeAt(index))
        rules.copy(rules = list)
    }

    override fun removeRule(index: Int) = updateRules { rules ->
        rules.copy(rules = rules.rules.filterIndexed { i, _ -> i != index })
    }

    private fun updateRules(change: (RoutingRules) -> RoutingRules) {
        state.update { it.copy(routing = it.routing.copy(rules = change(it.routing.rules)), saveFailed = false) }
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

        val validation = validator.validate(editingId ?: idGenerator.newId(), current.draft)
        val vpn = current.vpn
        val vpnErrors = buildSet {
            if (vpn.enabled && vpn.ovpn.isBlank()) add(ProfileField.VPN_FILE)
            if (vpn.enabled && vpn.needsCredentials && vpn.username.isBlank()) add(ProfileField.VPN_USERNAME)
        }
        when {
            validation is ProfileValidation.Invalid -> state.update { it.copy(errors = validation.errors + vpnErrors) }
            vpnErrors.isNotEmpty() -> state.update { it.copy(errors = vpnErrors) }
            validation is ProfileValidation.Valid -> {
                state.update { it.copy(errors = emptySet(), isSaving = true, saveFailed = false) }
                viewModelScope.launch {
                    val profile = validation.profile.copy(
                        vpn = if (vpn.enabled) VpnProfile(vpn.fileName, vpn.username.trim()) else null,
                        routing = current.routing.rules,
                    )
                    val secrets = if (vpn.enabled) VpnSecrets(vpn.ovpn, vpn.password) else null
                    val ok = guarded("guardar el perfil") { repository.save(profile, current.password, secrets) } != null
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

    private fun needsCredentials(ovpn: String): Boolean = try {
        parseOvpn(ovpn).needsCredentials
    } catch (_: OvpnConfigException) {
        true
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
        val VPN_FIELDS = setOf(ProfileField.VPN_FILE, ProfileField.VPN_USERNAME)

        // La prueba no guarda nada: el perfil temporal solo necesita un id cualquiera.
        const val TEST_PROFILE_ID = "prueba"
    }
}
