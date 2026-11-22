package org.uci.proxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.connection.ConnectionNavigation
import org.uci.proxy.connection.ConnectionScreen
import org.uci.proxy.files.FilePicker
import org.uci.proxy.files.LocalFilePicker
import org.uci.proxy.logs.LogsScreen
import org.uci.proxy.navigation.Route
import org.uci.proxy.navigation.currentTab
import org.uci.proxy.navigation.isOnTab
import org.uci.proxy.navigation.routesConfiguration
import org.uci.proxy.navigation.selectTab
import org.uci.proxy.profile.ProfileEditorScreen
import org.uci.proxy.profile.ProfileListScreen
import org.uci.proxy.settings.SettingsScreen
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.catalog.ComponentCatalogScreen
import org.uci.proxy.ui.components.UciNavItem
import org.uci.proxy.ui.components.UciNavigationScaffold
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.usage.UsageScreen

/** Requiere que la app haya llamado antes a [org.uci.proxy.di.initKoin]. */
@Composable
@DevelopmentEntryPoint
fun App() = App(filePicker = null)

/**
 * @param filePicker el selector de archivos del sistema, o null donde no lo hay (iOS): sin él no se ofrece la VPN.
 * @param appCatalog las apps instaladas, o null donde no hay reglas por app (escritorio, iOS).
 * @param developerMode muestra herramientas de desarrollo, como el catálogo de componentes. Solo en compilaciones de
 * depuración (Android) o con la variable de entorno PROXYUCI_DEV=1 (escritorio).
 * @param titleBarInset el alto de la barra de título del sistema cuando la ventana dibuja por debajo de ella (macOS),
 * para no poner nada bajo sus botones.
 */
@Composable
fun App(
    filePicker: FilePicker?,
    appCatalog: AppCatalog? = null,
    developerMode: Boolean = false,
    titleBarInset: Dp = 0.dp,
) = CompositionLocalProvider(
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
                topInset = titleBarInset,
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
                            ConnectionScreen(
                                wide = wide,
                                navigation = ConnectionNavigation(
                                    onEditProfile = { backStack.add(Route.ProfileEditor(profileId = it)) },
                                    onAddProfile = { backStack.add(Route.ProfileEditor(profileId = null)) },
                                    onOpenLogs = { backStack.selectTab(Route.Logs) },
                                    onOpenUsage = { backStack.add(Route.Usage) },
                                ),
                            )
                        }
                        entry<Route.ProfileList> {
                            ProfileListScreen(
                                onAdd = { backStack.add(Route.ProfileEditor(profileId = null)) },
                                onEdit = { backStack.add(Route.ProfileEditor(profileId = it)) },
                                onOpenCatalog = if (developerMode) {
                                    { backStack.add(Route.Catalog) }
                                } else {
                                    null
                                },
                            )
                        }
                        entry<Route.Usage> {
                            UsageScreen(
                                onBack = { backStack.removeLastOrNull() },
                                onEditProfile = { backStack.add(Route.ProfileEditor(profileId = it)) },
                            )
                        }
                        entry<Route.Catalog> {
                            ComponentCatalogScreen(onBack = { backStack.removeLastOrNull() })
                        }
                        // Pestañas: no tienen «Volver»; atrás lleva a Conexión.
                        entry<Route.Settings> { SettingsScreen(developerMode = developerMode) }
                        entry<Route.Logs> { LogsScreen() }
                        entry<Route.ProfileEditor> { route ->
                            ProfileEditorScreen(
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
