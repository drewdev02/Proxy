package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.common.LOCAL_NETWORK_DENIED_FOR_TEST
import org.uci.proxy.common.message
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.files.LocalFilePicker
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciFileField
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons

/**
 * El editor de perfil a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado.
 * [profileId] null es un perfil nuevo; [onClose] se llama al guardar o cancelar.
 */
@Composable
internal fun ProfileEditorScreen(
    profileId: String?,
    onClose: () -> Unit,
    viewModel: ProfileEditorViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // El view model se reutiliza entre aperturas: cada vez que se entra se prepara para ese perfil.
    LaunchedEffect(profileId) { viewModel.start(profileId) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.savedHandled()
            onClose()
        }
    }

    val picker = LocalFilePicker.current
    val scope = rememberCoroutineScope()
    // Los contratos de las secciones se traducen aquí a las funciones del ViewModel: a las secciones no les llega el ViewModel.
    val exitActions = remember(viewModel) { viewModel.countryExitActions() }
    val routingActions = remember(viewModel) { viewModel.routingActions() }

    ProfileEditorScreen(
        state = state,
        vpnAvailable = picker != null,
        onVpnPickFile = { scope.launch { picker?.pickText()?.let { viewModel.loadOvpnFile(it.name, it.text) } } },
        exitActions = exitActions,
        routingActions = routingActions,
        appCatalog = LocalAppCatalog.current,
        onDraftChange = viewModel::updateDraft,
        onPasswordChange = viewModel::updatePassword,
        onTest = viewModel::testProxy,
        onUciQuotaChange = viewModel::setUciQuota,
        onSave = viewModel::save,
        onCancel = onClose,
    )
}

private fun ProfileEditorViewModel.countryExitActions() = object : CountryExitActions {
    override fun onVpnEnabledChange(enabled: Boolean) = setCountryExitEnabled(enabled)
    override fun onEngineChange(engine: CountryEngineKind) = selectEngine(engine)
    override fun onVpnUsernameChange(username: String) = updateVpnUsername(username)
    override fun onVpnPasswordChange(password: String) = updateVpnPassword(password)
    override fun onTailscaleControlUrlChange(url: String) = updateTailscaleControlUrl(url)
    override fun onTailscaleAuthKeyChange(key: String) = updateTailscaleAuthKey(key)
    override fun onTailscaleExitNodeChange(node: String) = selectTailscaleExitNode(node)
    override fun onTailscaleHostnameChange(hostname: String) = updateTailscaleHostname(hostname)
    override fun searchExitNodes() = this@countryExitActions.searchExitNodes()
}

private fun ProfileEditorViewModel.routingActions() = object : RoutingActions {
    override fun onRoutingEnabledChange(enabled: Boolean) = setRoutingEnabled(enabled)
    override fun onDefaultExitChange(exit: RouteExit) = setDefaultExit(exit)
    override fun onNewRuleChange(text: String) = updateNewRule(text)
    override fun onNewRuleExitChange(exit: RouteExit) = setNewRuleExit(exit)
    override fun addRule() = this@routingActions.addRule()
    override fun addAppRule(packageName: String) = this@routingActions.addAppRule(packageName)
    override fun onRuleExitChange(index: Int, exit: RouteExit) = setRuleExit(index, exit)
    override fun moveRule(index: Int, delta: Int) = this@routingActions.moveRule(index, delta)
    override fun removeRule(index: Int) = this@routingActions.removeRule(index)
}

/**
 * El editor sin estado: dibuja [state] y avisa de lo que se pulsa. Una tarjeta por sección (servidor, autenticación,
 * Otro País, reglas de salida) y «Guardar» siempre a mano en la barra inferior.
 */
@Composable
fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    onDraftChange: (ProfileDraft) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** False donde no hay selector de archivos (iOS): las secciones de Otro País y reglas no se muestran. */
    vpnAvailable: Boolean = false,
    onVpnPickFile: () -> Unit = {},
    exitActions: CountryExitActions = CountryExitActions.None,
    routingActions: RoutingActions = RoutingActions.None,
    /** Las apps instaladas, para las reglas por app; null donde no se aplican. */
    appCatalog: AppCatalog? = null,
    onUciQuotaChange: (Boolean) -> Unit = {},
) {
    Scaffold(
        // Con el teclado abierto, la barra de Guardar sube sobre él: siempre a mano y sin un hueco vacío encima del
        // teclado. También permite desplazar el formulario hasta el final sin cerrarlo, que en iOS no siempre se puede.
        modifier = Modifier.imePadding(),
        topBar = {
            UciTopBar(
                title = if (state.isNew) "Nuevo perfil" else "Editar perfil",
                subtitle = state.draft.name.ifBlank { null },
                onBack = onCancel.takeIf { !state.isBusy },
            )
        },
        bottomBar = { SaveBar(state, onSave, onCancel) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ProxyUciTheme.spacing.screenMargin, vertical = ProxyUciTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md),
            ) {
                ServerSection(state, onDraftChange)
                AuthSection(state, onDraftChange, onPasswordChange, onTest, onUciQuotaChange)
                if (vpnAvailable) {
                    CountryExitSection(state, exitActions, onVpnPickFile)
                    RoutingSection(
                        routing = state.routing,
                        vpnEnabled = state.vpn.enabled,
                        enabled = !state.isBusy,
                        actions = routingActions,
                        appCatalog = appCatalog,
                    )
                }
                if (state.saveFailed) {
                    UciInlineAlert("No se pudo guardar el perfil. Inténtalo de nuevo.", tone = UciTone.Danger, title = "Sin guardar")
                }
            }
        }
    }
}

@Composable
private fun SaveBar(state: ProfileEditorUiState, onSave: () -> Unit, onCancel: () -> Unit) {
    Surface(color = ProxyUciTheme.colors.card) {
        Column {
            UciDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    // El hueco de la barra de navegación del sistema, salvo con el teclado abierto (ya lo cubre).
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = ProxyUciTheme.spacing.screenMargin, vertical = ProxyUciTheme.spacing.gutterSmall),
                horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UciButton("Cancelar", onCancel, variant = UciButtonVariant.Text, enabled = !state.isBusy)
                UciButton("Guardar", onSave, icon = UciIcons.Check, loading = state.isSaving, enabled = !state.isBusy)
            }
        }
    }
}

@Composable
private fun ServerSection(state: ProfileEditorUiState, onDraftChange: (ProfileDraft) -> Unit) {
    val draft = state.draft
    UciSectionCard(title = "Servidor proxy", subtitle = "A qué proxy se conecta este perfil", icon = UciIcons.Dns) {
        UciTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = "Nombre",
            placeholder = "p. ej. UCI Docente",
            error = "Escribe un nombre para el perfil.".takeIf { ProfileField.NAME in state.errors },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
            UciTextField(
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                label = "Servidor",
                placeholder = "p. ej. proxy.uci.cu",
                mono = true,
                error = "Solo el nombre o la IP, sin http:// ni espacios.".takeIf { ProfileField.HOST in state.errors },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            UciTextField(
                value = draft.port,
                onValueChange = { onDraftChange(draft.copy(port = it)) },
                label = "Puerto",
                placeholder = "3128",
                mono = true,
                error = "Entre 1 y 65535.".takeIf { ProfileField.PORT in state.errors },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text("Protocolo", style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        UciSegmentedControl(
            options = ProxyProtocol.entries,
            selected = draft.protocol,
            onSelect = { onDraftChange(draft.copy(protocol = it)) },
            label = { it.label },
        )
    }
}

@Composable
private fun AuthSection(
    state: ProfileEditorUiState,
    onDraftChange: (ProfileDraft) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onUciQuotaChange: (Boolean) -> Unit,
) {
    val draft = state.draft
    UciSectionCard(
        title = "Autenticación",
        subtitle = "Opcional. Se usa NTLM si el proxy lo ofrece, si no Basic",
        icon = UciIcons.Key,
    ) {
        UciTextField(
            value = draft.username,
            onValueChange = { onDraftChange(draft.copy(username = it)) },
            label = "Usuario",
            placeholder = "nombre.apellido",
            leadingIcon = UciIcons.Person,
        )
        UciPasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = "Contraseña",
            supportingText = "Se guarda en el almacén seguro del sistema",
        )
        UciButton(
            if (state.test == ProxyTestUiState.Running) "Probando…" else "Probar proxy",
            onTest,
            variant = UciButtonVariant.Secondary,
            icon = UciIcons.Speed,
            loading = state.test == ProxyTestUiState.Running,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val test = state.test) {
            ProxyTestUiState.Idle, ProxyTestUiState.Running -> Unit
            ProxyTestUiState.LocalNetworkDenied -> UciInlineAlert(LOCAL_NETWORK_DENIED_FOR_TEST, tone = UciTone.Warning)
            is ProxyTestUiState.Finished -> UciInlineAlert(
                test.result.message(),
                tone = when (test.result) {
                    ProxyTestResult.Success -> UciTone.Success
                    ProxyTestResult.Timeout -> UciTone.Warning
                    is ProxyTestResult.Failure -> UciTone.Danger
                },
            )
        }
        UciDivider()
        UciSwitchRow(
            title = "Consultar mi cuota de la UCI",
            subtitle = "Con este usuario y contraseña se pregunta al servicio de cuotas de la UCI cuántos MB te quedan " +
                "este mes. Se ve en Consumo.",
            checked = state.checksUciQuota,
            onCheckedChange = onUciQuotaChange,
            enabled = !state.isBusy,
        )
    }
}

/** «Otro País»: encadena tras el proxy un motor (OpenVPN o Tailscale). Opcional: apagado, el perfil sale directo. */
@Composable
private fun CountryExitSection(state: ProfileEditorUiState, actions: CountryExitActions, onPickFile: () -> Unit) {
    val vpn = state.vpn
    UciSectionCard(
        title = "Otro País",
        subtitle = "El tráfico sale por un servidor en otro país, pasando por el proxy",
        icon = UciIcons.Globe,
        tone = if (vpn.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = { UciSwitch(vpn.enabled, actions::onVpnEnabledChange, enabled = !state.isBusy, contentDescription = "Activar Otro País") },
    ) {
        if (!vpn.enabled) return@UciSectionCard
        if (state.engines.size > 1) {
            UciSegmentedControl(
                options = state.engines,
                selected = vpn.engine,
                onSelect = actions::onEngineChange,
                label = { it.label },
                description = { if (it == CountryEngineKind.OPENVPN) "archivo .ovpn" else "nodo de salida" },
                enabled = !state.isBusy,
            )
        }
        when (vpn.engine) {
            CountryEngineKind.OPENVPN -> OpenVpnFields(state, actions, onPickFile)
            CountryEngineKind.TAILSCALE -> TailscaleFields(state, actions)
        }
    }
}

@Composable
private fun OpenVpnFields(state: ProfileEditorUiState, actions: CountryExitActions, onPickFile: () -> Unit) {
    val vpn = state.vpn
    UciFileField(
        fileName = vpn.fileName.ifEmpty { null },
        onPick = onPickFile,
        detail = "OpenVPN sobre TCP",
        emptyText = "Ningún archivo .ovpn elegido",
        chooseLabel = "Elegir .ovpn",
        enabled = !state.isBusy,
    )
    val fileError = vpn.fileError?.message() ?: "Elige un archivo .ovpn (OpenVPN sobre TCP).".takeIf { ProfileField.VPN_FILE in state.errors }
    fileError?.let { UciInlineAlert(it, tone = UciTone.Danger) }
    if (vpn.needsCredentials) {
        UciTextField(
            value = vpn.username,
            onValueChange = actions::onVpnUsernameChange,
            label = "Usuario de la VPN",
            leadingIcon = UciIcons.Person,
            error = "Escribe el usuario de la VPN.".takeIf { ProfileField.VPN_USERNAME in state.errors },
        )
        UciPasswordField(value = vpn.password, onValueChange = actions::onVpnPasswordChange, label = "Contraseña de la VPN")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailscaleFields(state: ProfileEditorUiState, actions: CountryExitActions) {
    val tailscale = state.vpn.tailscale
    Text(
        "Sale por un nodo de salida de tu red de Tailscale (o de un servidor Headscale). Detrás del proxy todo va por " +
            "los relés de Tailscale: funciona, con algo más de latencia.",
        style = MaterialTheme.typography.bodySmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
    )
    UciTextField(
        value = tailscale.controlUrl,
        onValueChange = actions::onTailscaleControlUrlChange,
        label = "Servidor de control",
        mono = true,
        error = "Escribe una dirección http:// o https://.".takeIf { ProfileField.TAILSCALE_CONTROL_URL in state.errors },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    UciPasswordField(
        value = tailscale.authKey,
        onValueChange = actions::onTailscaleAuthKeyChange,
        label = "Clave de autenticación",
        placeholder = "tskey-auth-…",
        supportingText = "Créala en la consola de Tailscale (Configuración → Claves). Mejor reutilizable.",
        error = "Escribe la clave de autenticación.".takeIf { ProfileField.TAILSCALE_AUTH_KEY in state.errors },
    )
    UciTextField(
        value = tailscale.exitNode,
        onValueChange = actions::onTailscaleExitNodeChange,
        label = "Nodo de salida",
        placeholder = "Vacío: el único de la red",
        mono = true,
    )
    val searching = tailscale.search == ExitNodeSearch.Running
    UciButton(
        if (searching) "Buscando nodos…" else "Buscar nodos de salida",
        actions::searchExitNodes,
        variant = UciButtonVariant.Secondary,
        icon = UciIcons.Search,
        loading = searching,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    when (val search = tailscale.search) {
        is ExitNodeSearch.Found -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            search.nodes.forEach { node ->
                UciFilterChip(
                    label = listOfNotNull(node.name, node.place.ifEmpty { null }).joinToString(" · "),
                    selected = tailscale.exitNode.equals(node.selector, ignoreCase = true),
                    onClick = { actions.onTailscaleExitNodeChange(node.selector) },
                    dot = if (node.online) UciTone.Success else UciTone.Neutral,
                )
            }
        }
        is ExitNodeSearch.Failed -> UciInlineAlert(search.message, tone = UciTone.Danger)
        else -> Unit
    }
    UciTextField(
        value = tailscale.hostname,
        onValueChange = actions::onTailscaleHostnameChange,
        label = "Nombre de este equipo en la red",
        mono = true,
    )
}

internal val CountryEngineKind.label: String
    get() = when (this) {
        CountryEngineKind.OPENVPN -> "OpenVPN"
        CountryEngineKind.TAILSCALE -> "Tailscale"
    }

private fun ProxyTestUiState.message(): String? = when (this) {
    ProxyTestUiState.Idle, ProxyTestUiState.Running -> null
    ProxyTestUiState.LocalNetworkDenied -> LOCAL_NETWORK_DENIED_FOR_TEST
    is ProxyTestUiState.Finished -> result.message()
}

@Preview
@Composable
private fun ProfileEditorScreenPreview() {
    ProxyUciTheme {
        ProfileEditorScreen(
            state = ProfileEditorUiState(
                draft = ProfileDraft(name = "UCI", host = "http://proxy", port = "3128"),
                errors = setOf(ProfileField.HOST),
            ),
            onDraftChange = {},
            onPasswordChange = {},
            onTest = {},
            onSave = {},
            onCancel = {},
        )
    }
}
