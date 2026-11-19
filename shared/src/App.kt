package org.uci.proxy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.LocalAppCatalog
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.uci.proxy.connection.ConnectionNavigation
import org.uci.proxy.connection.ConnectionRoute
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.logs.LogsRoute
import org.uci.proxy.navigation.Route
import org.uci.proxy.navigation.currentTab
import org.uci.proxy.navigation.isOnTab
import org.uci.proxy.navigation.routesConfiguration
import org.uci.proxy.navigation.selectTab
import org.uci.proxy.ui.components.UciNavItem
import org.uci.proxy.ui.components.UciNavigationScaffold
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.profile.ProfileEditorScreen
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListScreen
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.profile.TunnelUiModel
import org.uci.proxy.settings.SettingsRoute
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.catalog.ComponentCatalogScreen
import org.uci.proxy.tunnel.TunnelViewModel

/** Requiere que la app haya llamado antes a [org.uci.proxy.di.initKoin]. */
@Composable
@DevelopmentEntryPoint
fun App() = App(filePicker = null)

/**
 * @param filePicker el selector de archivos del sistema, o null donde no lo hay (iOS): sin él no se ofrece la VPN.
 * @param appCatalog las apps instaladas, o null donde no hay reglas por app (escritorio, iOS).
 * @param developerMode muestra herramientas de desarrollo, como el catálogo de componentes. Solo en compilaciones de
 * depuración (Android) o con la variable de entorno PROXYUCI_DEV=1 (escritorio).
 */
@Composable
fun App(filePicker: FilePicker?, appCatalog: AppCatalog? = null, developerMode: Boolean = false) = CompositionLocalProvider(
    LocalFilePicker provides filePicker,
    LocalAppCatalog provides appCatalog,
) {
    ProxyUciTheme {
        val backStack = rememberNavBackStack(routesConfiguration, Route.Connection)
        val tabs = listOf<UciNavItem<Route>>(
            UciNavItem(Route.Connection, "Conexión", UciIcons.Shield),
            UciNavItem(Route.Logs, "Registros", UciIcons.Logs),
            UciNavItem(Route.ProfileList, "Perfiles", UciIcons.Badge),
            UciNavItem(Route.Settings, "Ajustes", UciIcons.Tune),
        )

        Surface(color = MaterialTheme.colorScheme.background) {
            UciNavigationScaffold(
                items = tabs,
                selected = backStack.currentTab(),
                onSelect = { backStack.selectTab(it) },
                // Las pantallas de detalle (editor, catálogo) ocupan todo, sin barra.
                showNavigation = backStack.isOnTab(),
            ) { wide ->
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
                        entry<Route.Connection> {
                            ConnectionRoute(
                                wide = wide,
                                navigation = ConnectionNavigation(
                                    onEditProfile = { backStack.add(Route.ProfileEditor(profileId = it)) },
                                    onAddProfile = { backStack.add(Route.ProfileEditor(profileId = null)) },
                                    onOpenLogs = { backStack.selectTab(Route.Logs) },
                                ),
                            )
                        }
                        entry<Route.ProfileList> {
                            ProfileListRoute(
                                onAdd = { backStack.add(Route.ProfileEditor(profileId = null)) },
                                onEdit = { backStack.add(Route.ProfileEditor(profileId = it)) },
                                onOpenLogs = { backStack.selectTab(Route.Logs) },
                                onOpenSettings = { backStack.selectTab(Route.Settings) },
                                onOpenCatalog = if (developerMode) {
                                    { backStack.add(Route.Catalog) }
                                } else {
                                    null
                                },
                            )
                        }
                        entry<Route.Catalog> {
                            ComponentCatalogScreen(onBack = { backStack.removeLastOrNull() })
                        }
                        // Pestañas: no tienen «Volver»; atrás lleva a Conexión.
                        entry<Route.Settings> { SettingsRoute() }
                        entry<Route.Logs> { LogsRoute() }
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
    }
}

@Composable
private fun ProfileListRoute(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCatalog: (() -> Unit)?,
) {
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
        onOpenCatalog = onOpenCatalog,
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
