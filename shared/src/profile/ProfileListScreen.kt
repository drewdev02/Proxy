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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.common.message
import org.uci.proxy.connection.address
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
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
import org.uci.proxy.common.StorageFailure

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
        platformLabel = stringResource(Res.string.profile_list_platform, state.platformName, state.osVersion),
        deleteFailed = state.deleteFailed,
        deleteFailure = state.deleteFailure,
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
    deleteFailure: StorageFailure? = null,
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
            title = stringResource(Res.string.profile_list_title),
            subtitle = when (profiles.size) {
                0 -> stringResource(Res.string.profile_list_none_saved)
                else -> pluralStringResource(Res.plurals.profile_list_saved_count, profiles.size, profiles.size)
            },
            actions = {
                if (onOpenCatalog != null) {
                    UciIconButton(UciIcons.Widgets, stringResource(Res.string.profile_list_open_catalog), onOpenCatalog, bordered = false)
                }
                UciButton(stringResource(Res.string.profile_list_new), onAdd, icon = UciIcons.Add)
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
                            stringResource(
                                if (deleteFailure == StorageFailure.SECURE_STORAGE) {
                                    Res.string.profile_list_delete_failed_secure_storage
                                } else {
                                    Res.string.profile_list_delete_failed
                                },
                            ),
                            title = stringResource(Res.string.profile_list_delete_failed_title),
                            tone = UciTone.Danger,
                            onDismiss = onDismissDeleteError,
                        )
                    }
                }
                if (profiles.isEmpty()) {
                    item(key = "empty") {
                        UciEmptyState(
                            title = stringResource(Res.string.profile_list_empty_title),
                            icon = UciIcons.Badge,
                            message = stringResource(Res.string.profile_list_empty_message),
                            action = { UciButton(stringResource(Res.string.profile_list_add), onAdd, icon = UciIcons.Add) },
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
            title = stringResource(Res.string.profile_list_delete_title, profile.name),
            message = stringResource(Res.string.profile_list_delete_message),
            confirmLabel = stringResource(Res.string.profile_list_delete_confirm),
            dismissLabel = stringResource(Res.string.profile_cancel),
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
            profile.countryExit?.let {
                UciStatusPill(stringResource(Res.string.profile_list_tag_country_exit, it.kind.label), tone = UciTone.Routing)
            }
            if (profile.routing.enabled) UciStatusPill(stringResource(Res.string.profile_list_tag_rules), tone = UciTone.Routing)
            when {
                connected?.vpnDown == true -> UciStatusPill(stringResource(Res.string.profile_list_tag_vpn_down), tone = UciTone.Warning)
                connected != null -> UciStatusPill(stringResource(Res.string.profile_list_tag_connected), tone = UciTone.Success)
                connecting -> UciStatusPill(stringResource(Res.string.profile_list_tag_connecting), tone = UciTone.Active)
            }
        },
        trailing = {
            if (tunnel.isAvailable) {
                when {
                    connected != null -> UciIconButton(
                        UciIcons.Power,
                        stringResource(Res.string.profile_list_disconnect_profile, profile.name),
                        onDisconnect,
                        tone = UciTone.Success,
                    )
                    connecting -> UciIconButton(
                        UciIcons.Power,
                        stringResource(Res.string.profile_list_connecting_profile, profile.name),
                        {},
                        tone = UciTone.Active,
                        enabled = false,
                    )
                    // Mientras otro perfil está en uso, primero hay que desconectarlo.
                    else -> UciIconButton(
                        UciIcons.Power,
                        stringResource(Res.string.profile_list_connect_profile, profile.name),
                        onConnect,
                        enabled = tunnel.activeProfileId == null,
                    )
                }
            }
            UciIconButton(
                UciIcons.Delete,
                stringResource(Res.string.profile_list_delete_profile, profile.name),
                onDelete,
                bordered = false,
                tone = UciTone.Danger,
                enabled = !isActive,
            )
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
