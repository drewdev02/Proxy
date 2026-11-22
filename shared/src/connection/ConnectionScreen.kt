package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.common.message
import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

/** Lo que la pantalla puede pedir a quien la contiene (navegar a otras pantallas). */
class ConnectionNavigation(
    val onEditProfile: (String) -> Unit = {},
    val onAddProfile: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onOpenUsage: () -> Unit = {},
)

/**
 * La pantalla Conexión a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado.
 * El ViewModel va como parámetro con valor por defecto, como recomienda Google, para poder sustituirlo en pruebas.
 */
@Composable
internal fun ConnectionScreen(
    wide: Boolean,
    navigation: ConnectionNavigation,
    viewModel: ConnectionViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionScreen(
        state = state,
        wide = wide,
        actions = ConnectionActions(
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onSelectProfile = viewModel::selectProfile,
            onTestProxy = viewModel::testProxy,
        ),
        navigation = navigation,
    )
}

/** Las acciones sobre el túnel y el perfil. */
class ConnectionActions(
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onSelectProfile: (String) -> Unit = {},
    val onTestProxy: () -> Unit = {},
)

/**
 * La pantalla principal, sin estado: solo dibuja [state] y avisa de lo que se pulsa. En móvil, una columna; con [wide]
 * (tableta, escritorio), dos: a la izquierda el perfil y el botón de conexión, a la derecha la prueba, las reglas y la
 * actividad.
 */
@Composable
internal fun ConnectionScreen(
    state: ConnectionUiState,
    wide: Boolean,
    actions: ConnectionActions = ConnectionActions(),
    navigation: ConnectionNavigation = ConnectionNavigation(),
) {
    var choosingProfile by remember { mutableStateOf(false) }
    val spacing = ProxyUciTheme.spacing
    val margin = if (wide) spacing.screenMarginWide else spacing.screenMargin

    Column(
        Modifier
            .fillMaxSize()
            // La barra inferior o lateral ya se ocupa de su borde: aquí solo arriba y a los lados.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Header(state, navigation.onOpenUsage, Modifier.padding(horizontal = margin, vertical = spacing.md))

        val profile = state.activeProfile
        if (profile == null) {
            UciEmptyState(
                title = "Aún no hay perfiles",
                icon = UciIcons.Badge,
                message = "Añade el proxy de tu facultad o residencia para empezar a navegar a través de él.",
                action = { UciButton("Añadir perfil", navigation.onAddProfile, icon = UciIcons.Add) },
                modifier = Modifier.padding(top = spacing.xl),
            )
            return@Column
        }

        val main: @Composable ColumnScope.() -> Unit = {
            ActiveProfileCard(state, profile, onChange = { choosingProfile = true }, onEdit = { navigation.onEditProfile(profile.id) })
            PowerArea(state, actions)
            StatusAlerts(state, actions, onEdit = { navigation.onEditProfile(profile.id) })
            if (state.tunnel is TunnelState.Connected) LiveTrafficSection(state.traffic, navigation.onOpenUsage)
        }
        val details: @Composable ColumnScope.() -> Unit = {
            ProxyCheckCard(state, actions.onTestProxy)
            RoutingCard(state.routing, onEdit = { navigation.onEditProfile(profile.id) })
            RecentActivity(state.recentActivity, navigation.onOpenLogs)
        }

        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = margin),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                ScrollColumn(Modifier.weight(1f), content = main)
                ScrollColumn(Modifier.weight(1f), content = details)
            }
        } else {
            ScrollColumn(Modifier.fillMaxSize().padding(horizontal = margin)) {
                main()
                details()
            }
        }
    }

    if (choosingProfile) {
        ProfilePicker(
            state = state,
            onSelect = {
                actions.onSelectProfile(it)
                choosingProfile = false
            },
            onAdd = {
                choosingProfile = false
                navigation.onAddProfile()
            },
            onDismiss = { choosingProfile = false },
        )
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(bottom = ProxyUciTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md),
        content = content,
    )
}

@Composable
private fun Header(state: ConnectionUiState, onOpenUsage: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ProxyUCI", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
        // El consumo guardado se puede ver también sin conexión.
        if (state.tunnelAvailable) UciIconButton(UciIcons.BarChart, "Consumo", onOpenUsage, bordered = false)
        if (state.tunnelAvailable) {
            val (text, tone) = when (val tunnel = state.tunnel) {
                TunnelState.Disconnected -> "Sin túnel" to UciTone.Neutral
                is TunnelState.Connecting -> "Conectando" to UciTone.Active
                is TunnelState.Connected -> (if (tunnel.vpnDown) "Túnel · sin VPN" else "Túnel activo") to
                    (if (tunnel.vpnDown) UciTone.Warning else UciTone.Success)
                is TunnelState.Failed -> "Falló" to UciTone.Danger
            }
            UciStatusPill(text, tone = tone)
        }
    }
}

private val previewProfile = ProxyProfile(
    id = "p1",
    name = "UCI Docente",
    host = "proxy.uci.cu",
    port = 8080,
    protocol = ProxyProtocol.HTTP,
    username = "ana.perez",
    countryExit = CountryExit.OpenVpn("canada_tcp.ovpn", "vpnuser"),
    routing = RoutingRules(
        enabled = true,
        rules = listOf(
            RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN),
            RouteRule(RuleKind.APP, "com.whatsapp", RouteExit.DIRECT),
        ),
    ),
)

private val previewState = ConnectionUiState(
    profiles = listOf(previewProfile),
    activeProfile = previewProfile,
    recentActivity = listOf(
        LogEntry(2, 1_700_000_000_000, LogLevel.INFO, "Tunnel", "Túnel listo con el perfil UCI Docente"),
        LogEntry(1, 1_699_999_990_000, LogLevel.WARN, "Dns", "nip.io no respondió; se usa sslip.io"),
    ),
)

@Preview(widthDp = 412, heightDp = 900)
@Composable
private fun ConnectionScreenPhonePreview() {
    ProxyUciTheme { ConnectionScreen(previewState, wide = false) }
}

@Preview(widthDp = 412, heightDp = 900)
@Composable
private fun ConnectionScreenFailedPreview() {
    ProxyUciTheme { ConnectionScreen(previewState.copy(tunnel = TunnelState.Failed(TunnelFailure.PROXY_AUTH_FAILED)), wide = false) }
}

@Preview(widthDp = 1100, heightDp = 760)
@Composable
private fun ConnectionScreenDesktopPreview() {
    ProxyUciTheme {
        ConnectionScreen(
            previewState.copy(
                tunnel = TunnelState.Connected("p1"),
                check = ProxyCheck.Finished(ProxyTestResult.Success, 184),
            ),
            wide = true,
        )
    }
}
