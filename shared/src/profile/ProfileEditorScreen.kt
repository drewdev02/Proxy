package org.uci.proxy.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.files.LocalFilePicker
import org.uci.proxy.theme.ProxyUciTheme

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
            viewModel.onSavedHandled()
            onClose()
        }
    }

    val picker = LocalFilePicker.current
    val scope = rememberCoroutineScope()

    ProfileEditorScreen(
        state = state,
        vpnAvailable = picker != null,
        onVpnPickFile = { scope.launch { picker?.pickText()?.let { viewModel.onVpnFileChosen(it.name, it.text) } } },
        exitActions = viewModel,
        routingActions = viewModel,
        appCatalog = LocalAppCatalog.current,
        onDraftChange = viewModel::onDraftChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTest = viewModel::testProxy,
        onSave = viewModel::save,
        onCancel = onClose,
    )
}

/** El editor sin estado: dibuja [state] y avisa de lo que se pulsa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    onDraftChange: (ProfileDraft) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** False donde no hay selector de archivos (iOS): la sección de Otro País no se muestra. */
    vpnAvailable: Boolean = false,
    onVpnPickFile: () -> Unit = {},
    exitActions: CountryExitActions = CountryExitActions.None,
    routingActions: RoutingActions = RoutingActions.None,
    /** Las apps instaladas, para las reglas por app; null donde no se aplican. */
    appCatalog: AppCatalog? = null,
) {
    val draft = state.draft
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.isNew) "Nuevo perfil" else "Editar perfil") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // Deja libre el espacio del teclado: así el formulario se puede desplazar hasta
                // «Guardar» sin tener que cerrarlo, cosa que en iOS no siempre es posible.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileTextField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                error = "Escribe un nombre para el perfil.".takeIf { ProfileField.NAME in state.errors },
            )
            ProfileTextField(
                label = "Servidor",
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                error = "Escribe solo el nombre o la IP del servidor, sin http:// ni espacios."
                    .takeIf { ProfileField.HOST in state.errors },
                keyboardType = KeyboardType.Uri,
            )
            ProfileTextField(
                label = "Puerto",
                value = draft.port,
                onValueChange = { onDraftChange(draft.copy(port = it)) },
                error = "El puerto debe ser un número entre 1 y 65535.".takeIf { ProfileField.PORT in state.errors },
                keyboardType = KeyboardType.Number,
            )

            Text("Protocolo", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyProtocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = draft.protocol == protocol,
                        onClick = { onDraftChange(draft.copy(protocol = protocol)) },
                        label = { Text(protocol.label) },
                    )
                }
            }

            ProfileTextField(
                label = "Usuario (opcional)",
                value = draft.username,
                onValueChange = { onDraftChange(draft.copy(username = it)) },
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Contraseña (opcional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(onClick = onTest, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.test == ProxyTestUiState.Running) "Probando…" else "Probar proxy")
            }
            state.test.message()?.let { message ->
                val succeeded = state.test == ProxyTestUiState.Finished(ProxyTestResult.Success)
                Text(
                    message,
                    color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (vpnAvailable) {
                VpnSection(state = state, actions = exitActions, onPickFile = onVpnPickFile)
                RoutingSection(
                    routing = state.routing,
                    vpnEnabled = state.vpn.enabled,
                    enabled = !state.isBusy,
                    actions = routingActions,
                    appCatalog = appCatalog,
                )
            }

            if (state.saveFailed) {
                Text(
                    "No se pudo guardar el perfil. Inténtalo de nuevo.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, enabled = !state.isBusy) { Text("Cancelar") }
                Button(onClick = onSave, enabled = !state.isBusy) { Text("Guardar") }
            }
        }
    }
}

/** «Salir por otro país»: encadena tras el proxy un motor (OpenVPN o Tailscale). Opcional: apagado, el perfil sale directo. */
@Composable
private fun VpnSection(
    state: ProfileEditorUiState,
    actions: CountryExitActions,
    onPickFile: () -> Unit,
) {
    val vpn = state.vpn
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = vpn.enabled, role = Role.Switch, onValueChange = actions::onVpnEnabledChange),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Salir por otro país", style = MaterialTheme.typography.titleMedium)
                Text(
                    "El tráfico sale por un servidor en otro país, pasando por el proxy (con reglas de salida, solo lo " +
                        "que elijas). Elige cómo: un archivo OpenVPN o un nodo de salida de Tailscale.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vpn.enabled, onCheckedChange = null)
        }

        if (vpn.enabled) {
            if (state.engines.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.engines.forEach { engine ->
                        FilterChip(
                            selected = vpn.engine == engine,
                            onClick = { actions.onEngineChange(engine) },
                            label = { Text(engine.label) },
                            enabled = !state.isBusy,
                        )
                    }
                }
            }
            when (vpn.engine) {
                CountryEngineKind.OPENVPN -> OpenVpnFields(state, actions, onPickFile)
                CountryEngineKind.TAILSCALE -> TailscaleFields(state, actions)
            }
        }
    }
}

@Composable
private fun OpenVpnFields(state: ProfileEditorUiState, actions: CountryExitActions, onPickFile: () -> Unit) {
    val vpn = state.vpn
    Text(
        "Solo perfiles OpenVPN sobre TCP.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onPickFile, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
        Text(if (vpn.fileName.isEmpty()) "Elegir archivo .ovpn" else "Archivo: ${vpn.fileName} (cambiar)")
    }
    val fileError = vpn.fileError
        ?: "Elige un archivo .ovpn.".takeIf { ProfileField.VPN_FILE in state.errors }
    fileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    if (vpn.needsCredentials) {
        ProfileTextField(
            label = "Usuario de la VPN",
            value = vpn.username,
            onValueChange = actions::onVpnUsernameChange,
            error = "Escribe el usuario de la VPN.".takeIf { ProfileField.VPN_USERNAME in state.errors },
        )
        OutlinedTextField(
            value = vpn.password,
            onValueChange = actions::onVpnPasswordChange,
            label = { Text("Contraseña de la VPN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TailscaleFields(state: ProfileEditorUiState, actions: CountryExitActions) {
    val tailscale = state.vpn.tailscale
    Text(
        "Sale por un nodo de salida de tu red de Tailscale (o de un servidor Headscale). Detrás del proxy todo va por " +
            "los relés de Tailscale: funciona, con algo más de latencia.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProfileTextField(
        label = "Servidor de control",
        value = tailscale.controlUrl,
        onValueChange = actions::onTailscaleControlUrlChange,
        error = "Escribe una dirección http:// o https://.".takeIf { ProfileField.TAILSCALE_CONTROL_URL in state.errors },
        keyboardType = KeyboardType.Uri,
    )
    OutlinedTextField(
        value = tailscale.authKey,
        onValueChange = actions::onTailscaleAuthKeyChange,
        label = { Text("Clave de autenticación (tskey-auth-…)") },
        singleLine = true,
        isError = ProfileField.TAILSCALE_AUTH_KEY in state.errors,
        supportingText = {
            Text(
                if (ProfileField.TAILSCALE_AUTH_KEY in state.errors) "Escribe la clave de autenticación."
                else "Créala en la consola de Tailscale (Configuración → Claves). Mejor reutilizable.",
            )
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    ProfileTextField(
        label = "Nodo de salida (vacío: el único de la red)",
        value = tailscale.exitNode,
        onValueChange = actions::onTailscaleExitNodeChange,
    )
    OutlinedButton(
        onClick = actions::searchExitNodes,
        enabled = !state.isBusy && tailscale.search != ExitNodeSearch.Running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (tailscale.search == ExitNodeSearch.Running) "Buscando nodos…" else "Buscar nodos de salida")
    }
    when (val search = tailscale.search) {
        is ExitNodeSearch.Found -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            search.nodes.forEach { node ->
                FilterChip(
                    selected = tailscale.exitNode.equals(node.selector, ignoreCase = true),
                    onClick = { actions.onTailscaleExitNodeChange(node.selector) },
                    label = {
                        Text(listOfNotNull(node.name, node.place.ifEmpty { null }, "sin conexión".takeIf { !node.online }).joinToString(" · "))
                    },
                )
            }
        }
        is ExitNodeSearch.Failed -> Text(search.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else -> Unit
    }
    ProfileTextField(
        label = "Nombre de este equipo en la red",
        value = tailscale.hostname,
        onValueChange = actions::onTailscaleHostnameChange,
    )
}

internal val CountryEngineKind.label: String
    get() = when (this) {
        CountryEngineKind.OPENVPN -> "OpenVPN"
        CountryEngineKind.TAILSCALE -> "Tailscale"
    }

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
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
