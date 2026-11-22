package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.files.LocalFilePicker
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.common.StorageFailure

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
                        stringResource(
                            if (state.saveFailure == StorageFailure.SECURE_STORAGE) {
                                Res.string.profile_editor_save_failed_secure_storage
                            } else {
                                Res.string.profile_editor_save_failed
                            },
                        ),
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
