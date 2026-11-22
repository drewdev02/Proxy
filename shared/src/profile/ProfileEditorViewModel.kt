package org.uci.proxy.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.uci.proxy.common.exitSearchReason
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.common.IdGenerator
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.port.ProfileRepository
import org.uci.proxy.core.profile.ProfileValidation
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.proxy.ProxyTester
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.port.SettingsRepository
import org.uci.proxy.core.vpn.CountryEngineCatalog
import org.uci.proxy.core.vpn.OvpnConfigException
import org.uci.proxy.core.vpn.parseOvpn
import org.uci.proxy.core.vpn.tailscale.ExitNodeListing
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
import org.uci.proxy.common.classifyStorageError

class ProfileEditorViewModel(
    private val repository: ProfileRepository,
    private val validator: ProfileValidator,
    private val idGenerator: IdGenerator,
    private val proxyTester: ProxyTester,
    private val localNetworkPermission: LocalNetworkPermission,
    private val logger: Logger,
    /** Qué motores de Otro País hay, y la búsqueda de nodos de Tailscale. */
    private val catalog: CountryEngineCatalog = CountryEngineCatalog.OpenVpnOnly,
    /** Los ajustes con los que se busca: cómo pedirle al proxy las IP públicas. */
    private val settings: SettingsRepository? = null,
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
        val engines = catalog.available.ifEmpty { listOf(CountryEngineKind.OPENVPN) }
        state.value = ProfileEditorUiState(
            isNew = existing == null,
            draft = existing?.toDraft() ?: ProfileDraft(),
            vpn = existing?.countryExit?.toDraft() ?: VpnDraft(engine = engines.first()),
            routing = RoutingDraft(
                rules = existing?.routing ?: RoutingRules(),
                // Una regla nueva va por Otro País solo si el perfil lo tiene; si no, esa salida no se puede elegir.
                newRuleExit = if (existing?.countryExit != null) RouteExit.VPN else RouteExit.PROXY,
            ),
            engines = engines,
            uciQuota = existing?.uciQuota,
        )
        if (existing != null) {
            viewModelScope.launch {
                val password = guarded("leer la contraseña") { repository.password(existing.id) }.orEmpty()
                val secrets = if (existing.countryExit != null) guarded("leer los secretos de Otro País") { repository.exitSecrets(existing.id) } else null
                // Si entretanto se abrió otro perfil, estos datos ya no corresponden al formulario.
                if (editingId == existing.id) {
                    state.update {
                        it.copy(
                            password = password,
                            vpn = when (secrets) {
                                is CountryExitSecrets.OpenVpn -> it.vpn.copy(
                                    ovpn = secrets.ovpn,
                                    password = secrets.password,
                                    needsCredentials = needsCredentials(secrets.ovpn),
                                )
                                is CountryExitSecrets.Tailscale -> it.vpn.copy(tailscale = it.vpn.tailscale.copy(authKey = secrets.authKey))
                                null -> it.vpn
                            },
                        )
                    }
                }
            }
        }
    }

    fun updateDraft(draft: ProfileDraft) {
        // Al corregir un campo se retira su error; el resto se revalida al guardar.
        // El resultado de una prueba anterior deja de valer en cuanto cambian los datos.
        state.update { current ->
            current.copy(
                draft = draft,
                errors = current.errors - changedFields(current.draft, draft),
                saveFailure = null,
                test = current.test.afterEdit(),
            )
        }
    }

    fun updatePassword(password: String) {
        state.update { it.copy(password = password, saveFailure = null, test = it.test.afterEdit()) }
    }

    fun setCountryExitEnabled(enabled: Boolean) {
        state.update {
            it.copy(
                vpn = it.vpn.copy(enabled = enabled),
                routing = it.routing.withNewRuleExitFor(enabled),
                errors = it.errors - VPN_FIELDS,
                saveFailure = null,
            )
        }
    }

    fun selectEngine(engine: CountryEngineKind) {
        if (engine !in state.value.engines) return
        state.update { it.copy(vpn = it.vpn.copy(engine = engine), errors = it.errors - VPN_FIELDS, saveFailure = null) }
    }

    fun updateTailscaleControlUrl(url: String) = updateTailscale(ProfileField.TAILSCALE_CONTROL_URL) {
        // Otra red: los nodos encontrados ya no valen.
        it.copy(controlUrl = url, search = ExitNodeSearch.Idle)
    }

    fun updateTailscaleAuthKey(key: String) = updateTailscale(ProfileField.TAILSCALE_AUTH_KEY) { it.copy(authKey = key) }

    fun selectTailscaleExitNode(node: String) = updateTailscale(null) { it.copy(exitNode = node) }

    fun updateTailscaleHostname(hostname: String) = updateTailscale(null) { it.copy(hostname = hostname) }

    private fun updateTailscale(field: ProfileField?, change: (TailscaleDraft) -> TailscaleDraft) {
        state.update {
            it.copy(
                vpn = it.vpn.copy(tailscale = change(it.vpn.tailscale)),
                errors = if (field == null) it.errors else it.errors - field,
                saveFailure = null,
            )
        }
    }

    /**
     * Busca los nodos de salida de la red con la clave escrita, a través del proxy del formulario (sin guardarlo). Si solo
     * hay uno y no se eligió ninguno, queda elegido.
     */
    fun searchExitNodes() {
        val current = state.value
        if (current.vpn.tailscale.search == ExitNodeSearch.Running) return
        val tailscale = current.vpn.tailscale
        val errors = tailscaleErrors(tailscale)
        val validation = validator.validate(editingId ?: TEST_PROFILE_ID, current.draft)
        if (validation is ProfileValidation.Invalid || errors.isNotEmpty()) {
            state.update { it.copy(errors = (validation as? ProfileValidation.Invalid)?.errors.orEmpty() + errors) }
            return
        }
        val profile = (validation as ProfileValidation.Valid).profile
        updateTailscale(null) { it.copy(search = ExitNodeSearch.Running) }
        viewModelScope.launch {
            val outcome = if (!localNetworkPermission.request()) {
                ExitNodeSearch.Failed(Res.string.error_tunnel_local_network_denied)
            } else {
                val listing = guarded("buscar los nodos de salida") {
                    catalog.listTailscaleExitNodes(
                        proxy = profile,
                        proxyPassword = current.password,
                        settings = settings?.settings?.value ?: AppSettings(),
                        exit = tailscale.toExit(),
                        secrets = CountryExitSecrets.Tailscale(tailscale.authKey.trim()),
                    )
                }
                when (listing) {
                    is ExitNodeListing.Found ->
                        if (listing.nodes.isEmpty()) ExitNodeSearch.Failed(Res.string.error_exit_search_no_nodes) else ExitNodeSearch.Found(listing.nodes)
                    is ExitNodeListing.Failed -> ExitNodeSearch.Failed(listing.failure.exitSearchReason)
                    null -> ExitNodeSearch.Failed(Res.string.error_exit_search_failed)
                }
            }
            state.update {
                if (it.vpn.tailscale.search != ExitNodeSearch.Running) return@update it
                val found = (outcome as? ExitNodeSearch.Found)?.nodes
                val autoPick = if (found?.size == 1 && it.vpn.tailscale.exitNode.isBlank()) found.single().selector else it.vpn.tailscale.exitNode
                it.copy(vpn = it.vpn.copy(tailscale = it.vpn.tailscale.copy(search = outcome, exitNode = autoPick)))
            }
        }
    }

    private fun tailscaleErrors(tailscale: TailscaleDraft): Set<ProfileField> = buildSet {
        val url = tailscale.controlUrl.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.substringAfter("://").isBlank()) add(ProfileField.TAILSCALE_CONTROL_URL)
        if (tailscale.authKey.isBlank()) add(ProfileField.TAILSCALE_AUTH_KEY)
    }

    /** La persona eligió un archivo `.ovpn`: se comprueba enseguida, para avisar antes de guardar y no al conectar. */
    fun loadOvpnFile(fileName: String, text: String) {
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
            state.value.vpn.copy(fileName = fileName, ovpn = "", fileError = e.error)
        }
        state.update {
            it.copy(vpn = vpn, routing = it.routing.withNewRuleExitFor(vpn.enabled), errors = it.errors - ProfileField.VPN_FILE, saveFailure = null)
        }
    }

    fun updateVpnUsername(username: String) {
        state.update { it.copy(vpn = it.vpn.copy(username = username), errors = it.errors - ProfileField.VPN_USERNAME, saveFailure = null) }
    }

    fun updateVpnPassword(password: String) {
        state.update { it.copy(vpn = it.vpn.copy(password = password), saveFailure = null) }
    }

    fun setRoutingEnabled(enabled: Boolean) = updateRouting { it.withEnabled(enabled) }

    fun setDefaultExit(exit: RouteExit) = updateRouting { it.withDefaultExit(exit) }

    fun updateNewRule(text: String) {
        state.update { it.copy(routing = it.routing.withNewRule(text)) }
    }

    fun setNewRuleExit(exit: RouteExit) {
        state.update { it.copy(routing = it.routing.withNewRuleExit(exit)) }
    }

    /** Añade lo escrito al final de la lista; si no es un dominio ni una IP, solo lo marca como inválido. */
    fun addRule() {
        val rule = state.value.routing.parsedNewRule()
        if (rule == null) {
            state.update { it.copy(routing = it.routing.markInvalid()) }
            return
        }
        updateRouting { it.withRule(rule, clearText = true) }
    }

    fun addAppRule(packageName: String) {
        if (packageName.isBlank()) return
        updateRouting { it.withAppRule(packageName) }
    }

    fun setRuleExit(index: Int, exit: RouteExit) = updateRouting { it.withRuleExit(index, exit) }

    fun moveRule(index: Int, delta: Int) = updateRouting { it.withRuleMoved(index, delta) }

    fun removeRule(index: Int) = updateRouting { it.withRuleRemoved(index) }

    /** Un cambio en las reglas: un aviso de guardado fallido anterior ya no vale. */
    private fun updateRouting(change: (RoutingDraft) -> RoutingDraft) {
        state.update { it.copy(routing = change(it.routing), saveFailure = null) }
    }

    /**
     * La pantalla ya reaccionó al guardado y se cerró. Sin esto, [ProfileEditorUiState.saved] seguiría a true y, si el
     * sistema recreara la actividad antes de que este view model se descarte, la pantalla volvería a cerrarse.
     */
    fun savedHandled() {
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
        val vpnErrors = when {
            !vpn.enabled -> emptySet()
            vpn.engine == CountryEngineKind.TAILSCALE -> tailscaleErrors(vpn.tailscale)
            else -> buildSet {
                if (vpn.ovpn.isBlank()) add(ProfileField.VPN_FILE)
                if (vpn.needsCredentials && vpn.username.isBlank()) add(ProfileField.VPN_USERNAME)
            }
        }
        when {
            validation is ProfileValidation.Invalid -> state.update { it.copy(errors = validation.errors + vpnErrors) }
            vpnErrors.isNotEmpty() -> state.update { it.copy(errors = vpnErrors) }
            validation is ProfileValidation.Valid -> {
                state.update { it.copy(errors = emptySet(), isSaving = true, saveFailure = null) }
                viewModelScope.launch {
                    val (exit, secrets) = when {
                        !vpn.enabled -> null to null
                        vpn.engine == CountryEngineKind.TAILSCALE ->
                            vpn.tailscale.toExit() to CountryExitSecrets.Tailscale(vpn.tailscale.authKey.trim())
                        else -> CountryExit.OpenVpn(vpn.fileName, vpn.username.trim()) to CountryExitSecrets.OpenVpn(vpn.ovpn, vpn.password)
                    }
                    val profile = validation.profile.copy(countryExit = exit, routing = current.routing.rules, uciQuota = current.uciQuota)
                    val failure = try {
                        repository.save(profile, current.password, secrets)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.log(LogLevel.ERROR, TAG, "No se pudo guardar el perfil: ${e.message}")
                        classifyStorageError(e)
                    }
                    state.update { it.copy(isSaving = false, saved = failure == null, saveFailure = failure) }
                }
            }
        }
    }

    /** Consultar (o no) la cuota de la UCI con este perfil. */
    fun setUciQuota(enabled: Boolean) {
        state.update { it.copy(uciQuota = enabled, saveFailure = null) }
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

    private fun CountryExit.toDraft(): VpnDraft = when (this) {
        is CountryExit.OpenVpn -> VpnDraft(enabled = true, engine = CountryEngineKind.OPENVPN, fileName = fileName, username = username)
        is CountryExit.Tailscale -> VpnDraft(
            enabled = true,
            engine = CountryEngineKind.TAILSCALE,
            tailscale = TailscaleDraft(controlUrl = controlUrl, exitNode = exitNode, hostname = hostname),
        )
    }

    private fun TailscaleDraft.toExit() = CountryExit.Tailscale(
        controlUrl = controlUrl.trim().trimEnd('/'),
        exitNode = exitNode.trim(),
        hostname = hostname.trim().ifEmpty { CountryExit.Tailscale.DEFAULT_HOSTNAME },
    )

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
        val VPN_FIELDS = setOf(
            ProfileField.VPN_FILE,
            ProfileField.VPN_USERNAME,
            ProfileField.TAILSCALE_CONTROL_URL,
            ProfileField.TAILSCALE_AUTH_KEY,
        )

        // La prueba no guarda nada: el perfil temporal solo necesita un id cualquiera.
        const val TEST_PROFILE_ID = "prueba"
    }
}
