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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.common.LOCAL_NETWORK_DENIED_FOR_TEST
import org.uci.proxy.common.isFixedInProfile
import org.uci.proxy.common.message
import org.uci.proxy.common.reason
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.logs.formatLogTime
import org.uci.proxy.profile.label
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciIconBox
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPowerButton
import org.uci.proxy.ui.components.UciPowerState
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciSettingRow
import org.uci.proxy.ui.components.UciStatusDot
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

/** Lo que la pantalla puede pedir a quien la contiene (navegar a otras pantallas). */
class ConnectionNavigation(
    val onEditProfile: (String) -> Unit = {},
    val onAddProfile: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
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
        Header(state, Modifier.padding(horizontal = margin, vertical = spacing.md))

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
private fun Header(state: ConnectionUiState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ProxyUCI", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
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

@Composable
private fun ActiveProfileCard(state: ConnectionUiState, profile: ProxyProfile, onChange: () -> Unit, onEdit: () -> Unit) {
    val connected = state.tunnel is TunnelState.Connected
    // Toda la tarjeta abre el editor del perfil: así la dirección tiene sitio y no se corta en móvil.
    UciCard(Modifier.fillMaxWidth(), tone = if (connected) UciTone.Success else UciTone.Neutral, onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UciIconBox(UciIcons.Badge, tone = if (connected) UciTone.Success else UciTone.Neutral)
            Column(Modifier.weight(1f)) {
                Text("PERFIL", style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    profile.address(),
                    style = ProxyUciTheme.mono.code,
                    color = ProxyUciTheme.colors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.profiles.size > 1) {
                UciIconButton(
                    UciIcons.Swap,
                    if (state.canChangeProfile) "Cambiar de perfil" else "Desconecta para cambiar de perfil",
                    onChange,
                    enabled = state.canChangeProfile,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UciStatusPill(profile.protocol.name)
            // El motor importa: OpenVPN y Tailscale fallan y se configuran distinto.
            profile.countryExit?.let { UciStatusPill("Otro País · ${it.kind.label}", tone = UciTone.Routing) }
            if (profile.routing.enabled) UciStatusPill("Reglas", tone = UciTone.Routing)
        }
    }
}

@Composable
private fun PowerArea(state: ConnectionUiState, actions: ConnectionActions) {
    if (!state.tunnelAvailable) {
        UciInlineAlert(
            "En este sistema todavía no se puede crear el túnel. Puedes gestionar los perfiles y probar el proxy.",
            title = "Túnel no disponible",
        )
        return
    }
    val (powerState, label) = when (state.tunnel) {
        TunnelState.Disconnected -> UciPowerState.Idle to "Conectar"
        is TunnelState.Connecting -> UciPowerState.Connecting to "Conectando"
        is TunnelState.Connected -> UciPowerState.Connected to "Conectado"
        is TunnelState.Failed -> UciPowerState.Error to "Reintentar"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        UciPowerButton(
            state = powerState,
            label = label,
            onClick = {
                when (state.tunnel) {
                    is TunnelState.Connected, is TunnelState.Connecting -> actions.onDisconnect()
                    else -> actions.onConnect()
                }
            },
        )
        StatusLine(state)
    }
}

@Composable
private fun StatusLine(state: ConnectionUiState) {
    val text = when (state.tunnel) {
        TunnelState.Disconnected -> "Sin conexión · pulsa para conectar"
        is TunnelState.Connecting -> "Conectando con ${state.activeProfile?.host.orEmpty()}…"
        is TunnelState.Connected -> {
            // Si la VPN está caída lo explica el aviso de debajo; aquí no se repite.
            val since = state.connectedSince
            if (since == null) {
                "Conectado · pulsa para desconectar"
            } else {
                var elapsed by remember(since) { mutableStateOf(since.elapsedNow()) }
                LaunchedEffect(since) {
                    while (true) {
                        elapsed = since.elapsedNow()
                        delay(1.seconds)
                    }
                }
                "Conectado · ${formatElapsed(elapsed)}"
            }
        }
        is TunnelState.Failed -> "No se pudo conectar"
    }
    Text(
        text,
        style = ProxyUciTheme.mono.metricSmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** `usuario@host:puerto`, o `host:puerto` si el perfil no tiene usuario. */
internal fun ProxyProfile.address(): String =
    if (username.isEmpty()) "$host:$port" else "$username@$host:$port"

/** Un tiempo como `02:45:07` (horas sin límite: un túnel puede llevar días abierto). */
internal fun formatElapsed(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    fun two(value: Long) = value.toString().padStart(2, '0')
    return "${two(total / 3600)}:${two(total % 3600 / 60)}:${two(total % 60)}"
}

@Composable
private fun StatusAlerts(state: ConnectionUiState, actions: ConnectionActions, onEdit: () -> Unit) {
    when (val tunnel = state.tunnel) {
        is TunnelState.Failed -> UciInlineAlert(
            tunnel.failure.reason().replaceFirstChar { it.uppercase() },
            title = "No se pudo conectar",
            tone = UciTone.Danger,
            action = {
                if (tunnel.failure.isFixedInProfile) {
                    UciButton("Editar perfil", onEdit, variant = UciButtonVariant.Text)
                } else {
                    UciButton("Reintentar", actions.onConnect, variant = UciButtonVariant.Text)
                }
            },
        )
        is TunnelState.Connected -> if (tunnel.vpnDown) {
            UciInlineAlert(
                "Lo que debía salir por Otro País sale por el proxy mientras se reintenta la VPN.",
                title = "Otro País no responde",
                tone = UciTone.Warning,
            )
        }
        else -> Unit
    }
}

@Composable
private fun ProxyCheckCard(state: ConnectionUiState, onTest: () -> Unit) {
    val check = state.check
    UciSectionCard(
        title = "Probar el proxy",
        subtitle = "Comprueba el usuario y la contraseña con el proxy",
        icon = UciIcons.Speed,
        trailing = {
            UciButton(
                "Probar",
                onTest,
                variant = UciButtonVariant.Secondary,
                loading = check == ProxyCheck.Running,
            )
        },
    ) {
        when (check) {
            ProxyCheck.Idle, ProxyCheck.Running -> Unit
            ProxyCheck.LocalNetworkDenied -> UciInlineAlert(LOCAL_NETWORK_DENIED_FOR_TEST, tone = UciTone.Warning)
            is ProxyCheck.Finished -> when (check.result) {
                // El tiempo es el de la prueba entera (conectar, autenticarse, abrir el túnel), no una latencia: no se
                // le aplican los umbrales de latencia del diseño.
                ProxyTestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UciStatusPill("OK · ${check.millis} ms", tone = UciTone.Success, icon = UciIcons.Check)
                    Text("El proxy aceptó los datos del perfil.", style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
                }
                ProxyTestResult.Timeout -> UciInlineAlert(check.result.message(), tone = UciTone.Warning)
                is ProxyTestResult.Failure -> UciInlineAlert(check.result.message(), tone = UciTone.Danger)
            }
        }
    }
}

@Composable
private fun RoutingCard(summary: RoutingSummary?, onEdit: () -> Unit) {
    summary ?: return
    UciSectionCard(
        title = "Cómo sale el tráfico",
        subtitle = if (summary.enabled) "Reglas de salida activas" else "Sin reglas de salida",
        icon = UciIcons.Split,
        tone = if (summary.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = { UciButton("Editar", onEdit, variant = UciButtonVariant.Text) },
    ) {
        ExitRow(if (summary.enabled) "Por defecto" else "Todo", summary.defaultExit)
        RouteExit.entries.forEach { exit ->
            val kinds = summary.rules[exit] ?: return@forEach
            UciDivider()
            ExitRow(kinds.describe(), exit)
        }
    }
}

@Composable
private fun ExitRow(label: String, exit: RouteExit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        UciStatusPill(exit.label(), tone = exit.tone())
    }
}

private fun RouteExit.label(): String = when (this) {
    RouteExit.PROXY -> "Proxy"
    RouteExit.VPN -> "Otro País"
    RouteExit.DIRECT -> "Directo"
    RouteExit.BLOCK -> "Bloqueado"
}

private fun RouteExit.tone(): UciTone = when (this) {
    RouteExit.PROXY -> UciTone.Active
    RouteExit.VPN -> UciTone.Routing
    RouteExit.DIRECT -> UciTone.Neutral
    RouteExit.BLOCK -> UciTone.Danger
}

/** «2 sitios, 1 app» a partir de cuántas reglas hay de cada tipo. */
internal fun Map<RuleKind, Int>.describe(): String = RuleKind.entries.mapNotNull { kind ->
    val count = this[kind] ?: return@mapNotNull null
    val (one, many) = when (kind) {
        RuleKind.DOMAIN -> "sitio" to "sitios"
        RuleKind.IP -> "red" to "redes"
        RuleKind.APP -> "app" to "apps"
    }
    "$count ${if (count == 1) one else many}"
}.joinToString(", ")

@Composable
private fun RecentActivity(entries: List<LogEntry>, onOpenLogs: () -> Unit) {
    UciSectionHeader("Actividad reciente", trailing = { UciButton("Ver todo", onOpenLogs, variant = UciButtonVariant.Text) })
    UciCard(Modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            Text("Sin actividad todavía.", style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) UciDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                UciStatusDot(entry.level.tone(), modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatLogTime(entry.epochMillis).substringBefore('.'), style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
                        Text(entry.tag, style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceMuted)
                    }
                    Text(entry.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun LogLevel.tone(): UciTone = when (this) {
    LogLevel.ERROR -> UciTone.Danger
    LogLevel.WARN -> UciTone.Warning
    LogLevel.INFO -> UciTone.Active
    LogLevel.DEBUG -> UciTone.Neutral
}

@Composable
private fun ProfilePicker(state: ConnectionUiState, onSelect: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    UciBottomSheet("Elegir perfil", onDismiss = onDismiss) {
        UciCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            state.profiles.forEachIndexed { index, profile ->
                if (index > 0) UciDivider()
                val active = profile.id == state.activeProfile?.id
                UciSettingRow(
                    title = profile.name,
                    subtitle = "${profile.protocol.name} · ${profile.host}:${profile.port}",
                    icon = if (active) UciIcons.CheckCircle else UciIcons.Badge,
                    onClick = { onSelect(profile.id) },
                    trailing = if (active) {
                        { UciStatusPill("Activo", tone = UciTone.Active) }
                    } else {
                        null
                    },
                )
            }
        }
        UciButton("Añadir perfil", onAdd, variant = UciButtonVariant.Secondary, icon = UciIcons.Add, modifier = Modifier.fillMaxWidth())
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
