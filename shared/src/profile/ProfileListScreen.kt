package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.common.message
import org.uci.proxy.connection.address
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciConfirmDialog
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciProfileRow
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons

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

/** La lista de perfiles a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado. */
@Composable
internal fun ProfileListScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenCatalog: (() -> Unit)?,
    viewModel: ProfileListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ProfileListScreen(
        profiles = state.profiles,
        platformLabel = state.platformLabel,
        deleteFailed = state.deleteFailed,
        tunnel = state.tunnel,
        onAdd = onAdd,
        onEdit = onEdit,
        onOpenCatalog = onOpenCatalog,
        onDelete = viewModel::delete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
    )
}

/**
 * La lista de perfiles sin estado (salvo la confirmación de borrado): una tarjeta por perfil con su estado, y desde
 * ella editar, conectar o borrar. El perfil en uso no se edita ni se borra hasta desconectar.
 */
@Composable
fun ProfileListScreen(
    profiles: List<ProxyProfile>,
    platformLabel: String,
    deleteFailed: Boolean,
    tunnel: TunnelUiModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissDeleteError: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    /** Si no es null, se ofrece abrir el catálogo de componentes (solo en modo desarrollo). */
    onOpenCatalog: (() -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<ProxyProfile?>(null) }
    val spacing = ProxyUciTheme.spacing

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        UciTopBar(
            title = "Perfiles",
            subtitle = when (profiles.size) {
                0 -> "Ninguno guardado"
                1 -> "1 perfil guardado"
                else -> "${profiles.size} perfiles guardados"
            },
            actions = {
                if (onOpenCatalog != null) UciIconButton(UciIcons.Widgets, "Catálogo de componentes", onOpenCatalog, bordered = false)
                UciButton("Nuevo", onAdd, icon = UciIcons.Add)
            },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = spacing.screenMargin, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.gutterSmall),
            ) {
                (tunnel.state as? TunnelState.Failed)?.let { failed ->
                    item(key = "tunnel-failed") {
                        UciInlineAlert(failed.failure.message(), tone = UciTone.Danger)
                    }
                }
                if (deleteFailed) {
                    item(key = "delete-failed") {
                        UciInlineAlert(
                            "El perfil no se pudo eliminar. Inténtalo de nuevo.",
                            title = "No se pudo eliminar",
                            tone = UciTone.Danger,
                            onDismiss = onDismissDeleteError,
                        )
                    }
                }
                if (profiles.isEmpty()) {
                    item(key = "empty") {
                        UciEmptyState(
                            title = "Aún no hay perfiles",
                            icon = UciIcons.Badge,
                            message = "Un perfil guarda los datos de un proxy: servidor, usuario y, si quieres, Otro País y reglas de salida.",
                            action = { UciButton("Añadir perfil", onAdd, icon = UciIcons.Add) },
                        )
                    }
                }
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
                item(key = "platform") {
                    Text(
                        platformLabel,
                        style = ProxyUciTheme.mono.badge,
                        color = ProxyUciTheme.colors.onSurfaceFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md),
                    )
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        UciConfirmDialog(
            title = "¿Eliminar «${profile.name}»?",
            message = "Se eliminan también su contraseña y lo guardado de Otro País. No se puede deshacer.",
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(profile.id)
            },
            onDismiss = { pendingDelete = null },
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
    val connected = (tunnel.state as? TunnelState.Connected)?.takeIf { isActive }
    val connecting = isActive && tunnel.state is TunnelState.Connecting
    UciProfileRow(
        name = profile.name,
        address = profile.address(),
        status = when {
            connected != null -> UciTone.Success
            connecting -> UciTone.Active
            else -> UciTone.Neutral
        },
        statusPulsing = connecting,
        tone = if (connected != null) UciTone.Success else UciTone.Neutral,
        // El perfil en uso no se edita hasta desconectar.
        onClick = onEdit.takeIf { !isActive },
        tags = {
            UciStatusPill(profile.protocol.label)
            profile.countryExit?.let { UciStatusPill("Otro País · ${it.kind.label}", tone = UciTone.Routing) }
            if (profile.routing.enabled) UciStatusPill("Reglas", tone = UciTone.Routing)
            when {
                connected?.vpnDown == true -> UciStatusPill("Sin Otro País", tone = UciTone.Warning)
                connected != null -> UciStatusPill("Conectado", tone = UciTone.Success)
                connecting -> UciStatusPill("Conectando", tone = UciTone.Active)
            }
        },
        trailing = {
            if (tunnel.isAvailable) {
                when {
                    connected != null -> UciIconButton(UciIcons.Power, "Desconectar ${profile.name}", onDisconnect, tone = UciTone.Success)
                    connecting -> UciIconButton(UciIcons.Power, "Conectando ${profile.name}", {}, tone = UciTone.Active, enabled = false)
                    // Mientras otro perfil está en uso, primero hay que desconectarlo.
                    else -> UciIconButton(UciIcons.Power, "Conectar ${profile.name}", onConnect, enabled = tunnel.activeProfileId == null)
                }
            }
            UciIconButton(UciIcons.Delete, "Eliminar ${profile.name}", onDelete, bordered = false, tone = UciTone.Danger, enabled = !isActive)
        },
    )
}

internal val ProxyProtocol.label: String
    get() = when (this) {
        ProxyProtocol.HTTP -> "HTTP"
        ProxyProtocol.SOCKS5 -> "SOCKS5"
    }

@Preview(widthDp = 412, heightDp = 800)
@Composable
private fun ProfileListScreenPreview() {
    ProxyUciTheme {
        ProfileListScreen(
            profiles = listOf(
                ProxyProfile("1", "UCI Docente", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey"),
                ProxyProfile("2", "Residencia", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, ""),
            ),
            platformLabel = "ProxyUCI en Android 17",
            deleteFailed = false,
            tunnel = TunnelUiModel(isAvailable = true, state = TunnelState.Connected("1")),
            onAdd = {},
            onEdit = {},
            onDelete = {},
            onDismissDeleteError = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}
