package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState

/** Lo que la lista necesita saber del túnel. */
data class TunnelUiModel(
    /** False en las plataformas sin túnel: no se muestran los controles de conexión. */
    val isAvailable: Boolean,
    val state: TunnelState,
) {
    /** Perfil con el que se está conectando o ya conectado. */
    val activeProfileId: String?
        get() = when (state) {
            is TunnelState.Connecting -> state.profileId
            is TunnelState.Connected -> state.profileId
            else -> null
        }
}

/** Sin estado propio salvo el diálogo de confirmación: recibe los datos y avisa de las acciones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    profiles: List<ProxyProfile>,
    platformLabel: String,
    deleteFailed: Boolean,
    tunnel: TunnelUiModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: (String) -> Unit,
    onDismissDeleteError: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ProxyProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Perfiles de proxy") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Ajustes") }
                    TextButton(onClick = onOpenLogs) { Text("Registro") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text("Nuevo perfil") }
        },
        bottomBar = {
            Text(
                platformLabel,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            (tunnel.state as? TunnelState.Failed)?.let { failed ->
                Text(
                    failed.failure.message(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (profiles.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Todavía no hay perfiles. Crea uno con «Nuevo perfil».",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            tunnel = tunnel,
                            onEdit = { onEdit(profile.id) },
                            onDelete = { pendingDelete = profile },
                            onConnect = { onConnect(profile.id) },
                            onDisconnect = onDisconnect,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar perfil") },
            text = { Text("Se eliminará «${profile.name}» junto con su contraseña guardada.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(profile.id)
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }

    if (deleteFailed) {
        AlertDialog(
            onDismissRequest = onDismissDeleteError,
            title = { Text("No se pudo eliminar") },
            text = { Text("El perfil no se pudo eliminar. Inténtalo de nuevo.") },
            confirmButton = { TextButton(onClick = onDismissDeleteError) { Text("Aceptar") } },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProxyProfile,
    tunnel: TunnelUiModel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isActive = tunnel.activeProfileId == profile.id
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(profile.summary(), style = MaterialTheme.typography.bodyMedium)
            if (isActive && tunnel.state is TunnelState.Connected) {
                Text("Conectado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // El perfil en uso no se edita ni se borra hasta desconectar.
                TextButton(onClick = onEdit, enabled = !isActive) { Text("Editar") }
                TextButton(onClick = onDelete, enabled = !isActive) { Text("Eliminar") }
                if (tunnel.isAvailable) {
                    when {
                        isActive && tunnel.state is TunnelState.Connected ->
                            OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
                        isActive -> Button(onClick = {}, enabled = false) { Text("Conectando…") }
                        // Mientras otro perfil está en uso, primero hay que desconectarlo.
                        else -> Button(onClick = onConnect, enabled = tunnel.activeProfileId == null) { Text("Conectar") }
                    }
                }
            }
        }
    }
}

private fun TunnelFailure.message(): String = when (this) {
    TunnelFailure.VPN_PERMISSION_DENIED -> "No se pudo conectar: hace falta autorizar la conexión VPN."
    TunnelFailure.LOCAL_NETWORK_DENIED ->
        "No se pudo conectar: la app necesita permiso para acceder a la red local y alcanzar el proxy."
    TunnelFailure.PROFILE_NOT_FOUND -> "No se pudo conectar: el perfil ya no existe."
    TunnelFailure.INTERFACE_UNAVAILABLE -> "No se pudo conectar: el sistema no permitió crear la conexión VPN."
    TunnelFailure.PROXY_HOST_UNRESOLVED -> "No se pudo conectar: no se encuentra el servidor del perfil."
}

private fun ProxyProfile.summary(): String {
    val user = if (username.isEmpty()) "" else " · $username"
    return "${protocol.label} · $host:$port$user"
}

internal val ProxyProtocol.label: String
    get() = when (this) {
        ProxyProtocol.HTTP -> "HTTP"
        ProxyProtocol.SOCKS5 -> "SOCKS5"
    }

@Preview
@Composable
private fun ProfileListScreenPreview() {
    MaterialTheme {
        ProfileListScreen(
            profiles = listOf(ProxyProfile("1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")),
            platformLabel = "ProxyUCI en Android 17",
            deleteFailed = false,
            tunnel = TunnelUiModel(isAvailable = true, state = TunnelState.Connected("1")),
            onAdd = {},
            onEdit = {},
            onOpenLogs = {},
            onOpenSettings = {},
            onDelete = {},
            onDismissDeleteError = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}
