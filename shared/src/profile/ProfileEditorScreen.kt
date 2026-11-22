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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.common.LocalNetworkDeniedForTest
import org.uci.proxy.common.message
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.files.LocalFilePicker
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciFileField
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.common.FailureDetails
import org.uci.proxy.common.code
import org.uci.proxy.common.technicalDetail

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
                title = stringResource(if (state.isNew) Res.string.profile_editor_title_new else Res.string.profile_editor_title_edit),
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
                    UciInlineAlert(
                        stringResource(Res.string.profile_editor_save_failed),
                        tone = UciTone.Danger,
                        title = stringResource(Res.string.profile_editor_save_failed_title),
                    )
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
                UciButton(stringResource(Res.string.profile_cancel), onCancel, variant = UciButtonVariant.Text, enabled = !state.isBusy)
                UciButton(stringResource(Res.string.profile_editor_save), onSave, icon = UciIcons.Check, loading = state.isSaving, enabled = !state.isBusy)
            }
        }
    }
}

@Composable
private fun ServerSection(state: ProfileEditorUiState, onDraftChange: (ProfileDraft) -> Unit) {
    val draft = state.draft
    UciSectionCard(
        title = stringResource(Res.string.profile_server_title),
        subtitle = stringResource(Res.string.profile_server_subtitle),
        icon = UciIcons.Dns,
    ) {
        UciTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = stringResource(Res.string.profile_name_label),
            placeholder = stringResource(Res.string.profile_name_placeholder),
            error = state.errorFor(ProfileField.NAME),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
            UciTextField(
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                label = stringResource(Res.string.profile_host_label),
                placeholder = stringResource(Res.string.profile_host_placeholder),
                mono = true,
                error = state.errorFor(ProfileField.HOST),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            UciTextField(
                value = draft.port,
                onValueChange = { onDraftChange(draft.copy(port = it)) },
                label = stringResource(Res.string.profile_port_label),
                placeholder = "3128",
                mono = true,
                error = state.errorFor(ProfileField.PORT),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(Res.string.profile_protocol_label), style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
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
        title = stringResource(Res.string.profile_auth_title),
        subtitle = stringResource(Res.string.profile_auth_subtitle),
        icon = UciIcons.Key,
    ) {
        UciTextField(
            value = draft.username,
            onValueChange = { onDraftChange(draft.copy(username = it)) },
            label = stringResource(Res.string.profile_username_label),
            placeholder = stringResource(Res.string.profile_username_placeholder),
            leadingIcon = UciIcons.Person,
        )
        UciPasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(Res.string.profile_password_label),
            supportingText = stringResource(Res.string.profile_password_supporting),
        )
        UciButton(
            stringResource(if (state.test == ProxyTestUiState.Running) Res.string.profile_test_running else Res.string.profile_test_button),
            onTest,
            variant = UciButtonVariant.Secondary,
            icon = UciIcons.Speed,
            loading = state.test == ProxyTestUiState.Running,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val test = state.test) {
            ProxyTestUiState.Idle, ProxyTestUiState.Running -> Unit
            ProxyTestUiState.LocalNetworkDenied -> UciInlineAlert(stringResource(LocalNetworkDeniedForTest), tone = UciTone.Warning)
            is ProxyTestUiState.Finished -> Column {
                UciInlineAlert(
                    test.result.message(),
                    tone = when (test.result) {
                        ProxyTestResult.Success -> UciTone.Success
                        ProxyTestResult.Timeout -> UciTone.Warning
                        is ProxyTestResult.Failure -> UciTone.Danger
                    },
                )
                test.result.code?.let { FailureDetails(it, test.result.technicalDetail) }
            }
        }
        UciDivider()
        UciSwitchRow(
            title = stringResource(Res.string.profile_uci_quota_title),
            subtitle = stringResource(Res.string.profile_uci_quota_subtitle),
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
        title = stringResource(Res.string.profile_country_exit_title),
        subtitle = stringResource(Res.string.profile_country_exit_subtitle),
        icon = UciIcons.Globe,
        tone = if (vpn.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = {
            UciSwitch(
                vpn.enabled,
                actions::onVpnEnabledChange,
                enabled = !state.isBusy,
                contentDescription = stringResource(Res.string.profile_country_exit_toggle),
            )
        },
    ) {
        if (!vpn.enabled) return@UciSectionCard
        if (state.engines.size > 1) {
            val descriptions = CountryEngineKind.entries.associateWith { stringResource(it.description) }
            UciSegmentedControl(
                options = state.engines,
                selected = vpn.engine,
                onSelect = actions::onEngineChange,
                label = { it.label },
                description = { descriptions[it] },
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
        detail = stringResource(Res.string.profile_ovpn_detail),
        emptyText = stringResource(Res.string.profile_ovpn_empty),
        chooseLabel = stringResource(Res.string.profile_ovpn_choose),
        changeLabel = stringResource(Res.string.profile_ovpn_change),
        enabled = !state.isBusy,
    )
    val fileError = vpn.fileError?.message() ?: state.errorFor(ProfileField.VPN_FILE)
    fileError?.let { UciInlineAlert(it, tone = UciTone.Danger) }
    if (vpn.needsCredentials) {
        UciTextField(
            value = vpn.username,
            onValueChange = actions::onVpnUsernameChange,
            label = stringResource(Res.string.profile_vpn_username_label),
            leadingIcon = UciIcons.Person,
            error = state.errorFor(ProfileField.VPN_USERNAME),
        )
        UciPasswordField(
            value = vpn.password,
            onValueChange = actions::onVpnPasswordChange,
            label = stringResource(Res.string.profile_vpn_password_label),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailscaleFields(state: ProfileEditorUiState, actions: CountryExitActions) {
    val tailscale = state.vpn.tailscale
    Text(
        stringResource(Res.string.profile_tailscale_description),
        style = MaterialTheme.typography.bodySmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
    )
    UciTextField(
        value = tailscale.controlUrl,
        onValueChange = actions::onTailscaleControlUrlChange,
        label = stringResource(Res.string.profile_tailscale_control_url_label),
        mono = true,
        error = state.errorFor(ProfileField.TAILSCALE_CONTROL_URL),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    UciPasswordField(
        value = tailscale.authKey,
        onValueChange = actions::onTailscaleAuthKeyChange,
        label = stringResource(Res.string.profile_tailscale_auth_key_label),
        placeholder = "tskey-auth-…",
        supportingText = stringResource(Res.string.profile_tailscale_auth_key_supporting),
        error = state.errorFor(ProfileField.TAILSCALE_AUTH_KEY),
    )
    UciTextField(
        value = tailscale.exitNode,
        onValueChange = actions::onTailscaleExitNodeChange,
        label = stringResource(Res.string.profile_tailscale_exit_node_label),
        placeholder = stringResource(Res.string.profile_tailscale_exit_node_placeholder),
        mono = true,
    )
    val searching = tailscale.search == ExitNodeSearch.Running
    UciButton(
        stringResource(if (searching) Res.string.profile_tailscale_searching else Res.string.profile_tailscale_search),
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
        is ExitNodeSearch.Failed -> UciInlineAlert(stringResource(search.reason), tone = UciTone.Danger)
        else -> Unit
    }
    UciTextField(
        value = tailscale.hostname,
        onValueChange = actions::onTailscaleHostnameChange,
        label = stringResource(Res.string.profile_tailscale_hostname_label),
        mono = true,
    )
}

/** El nombre del motor: es una marca, no se traduce. */
internal val CountryEngineKind.label: String
    get() = when (this) {
        CountryEngineKind.OPENVPN -> "OpenVPN"
        CountryEngineKind.TAILSCALE -> "Tailscale"
    }

/** Lo que el motor pide, bajo su nombre en el selector. */
private val CountryEngineKind.description: StringResource
    get() = when (this) {
        CountryEngineKind.OPENVPN -> Res.string.profile_engine_openvpn_description
        CountryEngineKind.TAILSCALE -> Res.string.profile_engine_tailscale_description
    }

/**
 * Qué decir de un campo que no vale. La validación (en el núcleo) solo dice qué campo es; el texto se elige aquí. Sin
 * `else`: un campo nuevo no compila hasta que tenga su texto.
 */
private val ProfileField.error: StringResource
    get() = when (this) {
        ProfileField.NAME -> Res.string.profile_name_error
        ProfileField.HOST -> Res.string.profile_host_error
        ProfileField.PORT -> Res.string.profile_port_error
        ProfileField.VPN_FILE -> Res.string.error_ovpn_missing
        ProfileField.VPN_USERNAME -> Res.string.profile_vpn_username_error
        ProfileField.TAILSCALE_CONTROL_URL -> Res.string.profile_tailscale_control_url_error
        ProfileField.TAILSCALE_AUTH_KEY -> Res.string.profile_tailscale_auth_key_error
    }

/** El texto del error de [field], o null si ese campo está bien. */
@Composable
private fun ProfileEditorUiState.errorFor(field: ProfileField): String? =
    if (field in errors) stringResource(field.error) else null


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
