package org.uci.proxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.uci.proxy.files.FilePicker
import org.uci.proxy.files.LocalFilePicker
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.logs.LogsRoute
import org.uci.proxy.navigation.Route
import org.uci.proxy.navigation.routesConfiguration
import org.uci.proxy.profile.ProfileEditorScreen
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListScreen
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.profile.TunnelUiModel
import org.uci.proxy.settings.SettingsRoute
import org.uci.proxy.tunnel.TunnelViewModel

/** Requiere que la app haya llamado antes a [org.uci.proxy.di.initKoin]. */
@Composable
@DevelopmentEntryPoint
fun App() = App(filePicker = null)

/** @param filePicker el selector de archivos del sistema, o null donde no lo hay (iOS): sin él no se ofrece la VPN. */
@Composable
fun App(filePicker: FilePicker?) = CompositionLocalProvider(LocalFilePicker provides filePicker) {
    MaterialTheme {
        val backStack = rememberNavBackStack(routesConfiguration, Route.ProfileList)

        NavDisplay(
            backStack = backStack,
            // También atiende el botón y el gesto de atrás del sistema.
            onBack = { backStack.removeLastOrNull() },
            // Un ViewModel por pantalla: se descarta al salir de ella, y no se arrastra el estado de la vez anterior.
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Route.ProfileList> {
                    ProfileListRoute(
                        onAdd = { backStack.add(Route.ProfileEditor(profileId = null)) },
                        onEdit = { backStack.add(Route.ProfileEditor(profileId = it)) },
                        onOpenLogs = { backStack.add(Route.Logs) },
                        onOpenSettings = { backStack.add(Route.Settings) },
                    )
                }
                entry<Route.Settings> {
                    SettingsRoute(onBack = { backStack.removeLastOrNull() })
                }
                entry<Route.Logs> {
                    LogsRoute(onBack = { backStack.removeLastOrNull() })
                }
                entry<Route.ProfileEditor> { route ->
                    ProfileEditorRoute(
                        profileId = route.profileId,
                        onClose = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
    }
}

@Composable
private fun ProfileListRoute(onAdd: () -> Unit, onEdit: (String) -> Unit, onOpenLogs: () -> Unit, onOpenSettings: () -> Unit) {
    val viewModel = koinViewModel<ProfileListViewModel>()
    val home = koinViewModel<HomeViewModel>()
    val tunnel = koinViewModel<TunnelViewModel>()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val deleteFailed by viewModel.deleteFailed.collectAsStateWithLifecycle()
    val tunnelState by tunnel.state.collectAsStateWithLifecycle()

    ProfileListScreen(
        profiles = profiles,
        platformLabel = "ProxyUCI en ${home.uiState.platformName} ${home.uiState.osVersion}",
        deleteFailed = deleteFailed,
        tunnel = TunnelUiModel(isAvailable = tunnel.isAvailable, state = tunnelState),
        onAdd = onAdd,
        onEdit = onEdit,
        onOpenLogs = onOpenLogs,
        onOpenSettings = onOpenSettings,
        onDelete = viewModel::delete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        onConnect = tunnel::connect,
        onDisconnect = tunnel::disconnect,
    )
}

@Composable
private fun ProfileEditorRoute(profileId: String?, onClose: () -> Unit) {
    val viewModel = koinViewModel<ProfileEditorViewModel>()
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
        onVpnEnabledChange = viewModel::onVpnEnabledChange,
        onVpnPickFile = { scope.launch { picker?.pickText()?.let { viewModel.onVpnFileChosen(it.name, it.text) } } },
        onVpnUsernameChange = viewModel::onVpnUsernameChange,
        onVpnPasswordChange = viewModel::onVpnPasswordChange,
        routingActions = viewModel,
        onDraftChange = viewModel::onDraftChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTest = viewModel::testProxy,
        onSave = viewModel::save,
        onCancel = onClose,
    )
}
