package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.common.LocalNetworkDeniedForTest
import org.uci.proxy.common.FailureAction
import org.uci.proxy.common.FailureDetails
import org.uci.proxy.common.SystemSettings
import org.uci.proxy.common.SystemSettingsPage
import org.uci.proxy.common.action
import org.uci.proxy.common.code
import org.uci.proxy.common.technicalDetail
import org.uci.proxy.common.message
import org.uci.proxy.common.percentText
import org.uci.proxy.common.reasonText
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.ProxyProblem
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.settings.QuotaSettings
import org.uci.proxy.logs.formatLogTime
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.quota.wholePercent
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
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
import org.uci.proxy.core.port.code
import org.koin.compose.koinInject

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
    systemSettings: SystemSettings = koinInject(),
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
            systemSettings = systemSettings,
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
    /** Para llevar a los ajustes del sistema cuando un fallo se arregla allí; None donde no se puede. */
    val systemSettings: SystemSettings = SystemSettings.None,
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
                title = stringResource(Res.string.connection_empty_title),
                icon = UciIcons.Badge,
                message = stringResource(Res.string.connection_empty_message),
                action = { UciButton(stringResource(Res.string.connection_add_profile), navigation.onAddProfile, icon = UciIcons.Add) },
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
        if (state.tunnelAvailable) UciIconButton(UciIcons.BarChart, stringResource(Res.string.connection_open_usage), onOpenUsage, bordered = false)
        if (state.tunnelAvailable) {
            val (text, tone) = when (val tunnel = state.tunnel) {
                TunnelState.Disconnected -> Res.string.connection_pill_disconnected to UciTone.Neutral
                is TunnelState.Connecting -> Res.string.connection_pill_connecting to UciTone.Active
                is TunnelState.Connected -> when {
                    tunnel.proxyProblem != null -> Res.string.connection_pill_proxy_failing to UciTone.Danger
                    tunnel.vpnDown -> Res.string.connection_pill_vpn_down to UciTone.Warning
                    else -> Res.string.connection_pill_connected to UciTone.Success
                }
                is TunnelState.Failed -> Res.string.connection_pill_failed to UciTone.Danger
            }
            UciStatusPill(stringResource(text), tone = tone)
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
                Text(stringResource(Res.string.connection_profile_label), style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
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
                    stringResource(if (state.canChangeProfile) Res.string.connection_change_profile else Res.string.connection_change_profile_disconnect),
                    onChange,
                    enabled = state.canChangeProfile,
                )
            }
        }
        // Las etiquetas saltan de línea si no caben (perfil con Otro País, reglas y cuota en un móvil estrecho).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UciStatusPill(profile.protocol.name)
            // El motor importa: OpenVPN y Tailscale fallan y se configuran distinto.
            profile.countryExit?.let { UciStatusPill(stringResource(Res.string.connection_pill_country, it.kind.label), tone = UciTone.Routing) }
            if (profile.routing.enabled) UciStatusPill(stringResource(Res.string.connection_pill_rules), tone = UciTone.Routing)
            // La cuota de la UCI, si este perfil la consulta y ya se sabe.
            state.quota.quota?.takeIf { state.quota.enabled && state.quota.profileId == profile.id }?.let { quota ->
                UciStatusPill(stringResource(Res.string.connection_pill_quota, percentText(quota.wholePercent)), tone = quota.tone)
            }
        }
    }
}

@Composable
private fun PowerArea(state: ConnectionUiState, actions: ConnectionActions) {
    if (!state.tunnelAvailable) {
        UciInlineAlert(
            stringResource(Res.string.connection_unavailable_message),
            title = stringResource(Res.string.connection_unavailable_title),
        )
        return
    }
    val (powerState, label) = when (state.tunnel) {
        TunnelState.Disconnected -> UciPowerState.Idle to Res.string.connection_power_connect
        is TunnelState.Connecting -> UciPowerState.Connecting to Res.string.connection_power_connecting
        is TunnelState.Connected -> UciPowerState.Connected to Res.string.connection_power_connected
        is TunnelState.Failed -> UciPowerState.Error to Res.string.connection_power_retry
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        UciPowerButton(
            state = powerState,
            label = stringResource(label),
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
        TunnelState.Disconnected -> stringResource(Res.string.connection_status_disconnected)
        is TunnelState.Connecting -> stringResource(Res.string.connection_status_connecting, state.activeProfile?.host.orEmpty())
        is TunnelState.Connected -> {
            // Si la VPN está caída lo explica el aviso de debajo; aquí no se repite.
            val since = state.connectedSince
            if (since == null) {
                stringResource(Res.string.connection_status_connected)
            } else {
                var elapsed by remember(since) { mutableStateOf(since.elapsedNow()) }
                LaunchedEffect(since) {
                    while (true) {
                        elapsed = since.elapsedNow()
                        delay(1.seconds)
                    }
                }
                stringResource(Res.string.connection_status_connected_for, formatElapsed(elapsed))
            }
        }
        is TunnelState.Failed -> stringResource(Res.string.connection_status_failed)
    }
    Text(
        text,
        style = ProxyUciTheme.mono.metricSmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Los nombres que no resolvió el DNS de Otro País, para el aviso: los últimos [ViaProxyHosts.names] y cuántos más hay. */
internal data class ViaProxyHosts(val names: List<String>, val rest: Int)

/** Los [shown] nombres más recientes, del último al primero, y cuántos quedan sin nombrar. */
internal fun viaProxyHosts(hosts: List<String>, shown: Int = 3): ViaProxyHosts =
    ViaProxyHosts(hosts.takeLast(shown).asReversed(), hosts.size - minOf(hosts.size, shown))

/** «El DNS de Otro País no resolvió a.com, b.com y 3 más: …», con los primeros nombres y cuántos quedan. */
@Composable
private fun viaProxyMessage(hosts: List<String>): String {
    val (names, rest) = viaProxyHosts(hosts)
    val list = names.joinToString(", ")
    return if (rest > 0) {
        pluralStringResource(Res.plurals.connection_via_proxy_message_more, rest, list, rest)
    } else {
        stringResource(Res.string.connection_via_proxy_message, list)
    }
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
    if (state.quota.paused && state.tunnel !is TunnelState.Connected) {
        UciInlineAlert(
            stringResource(Res.string.connection_quota_paused_message, QuotaSettings.PAUSE_THRESHOLD, QuotaSettings.PAUSE_THRESHOLD - 5),
            title = stringResource(Res.string.connection_quota_paused_title),
            tone = UciTone.Danger,
            action = { UciButton(stringResource(Res.string.connection_connect_anyway), actions.onConnect, variant = UciButtonVariant.Text) },
        )
    }
    when (val tunnel = state.tunnel) {
        is TunnelState.Failed -> Column {
            UciInlineAlert(
                tunnel.failure.reasonText(),
                title = stringResource(Res.string.connection_failed_title),
                tone = UciTone.Danger,
                action = { FailureActionButton(tunnel.failure.action, actions, onEdit) },
            )
            FailureDetails(tunnel.failure.code)
        }
        is TunnelState.Connected -> when (tunnel.proxyProblem) {
            // Lo más grave primero: con el proxy fallando, no carga nada de lo que pasa por él.
            ProxyProblem.AUTH_REJECTED -> UciInlineAlert(
                stringResource(Res.string.connection_proxy_auth_rejected_message),
                title = stringResource(Res.string.connection_proxy_failing_title),
                tone = UciTone.Danger,
                action = { UciButton(stringResource(Res.string.connection_edit_profile), onEdit, variant = UciButtonVariant.Text) },
            )
            ProxyProblem.UNREACHABLE -> UciInlineAlert(
                stringResource(Res.string.connection_proxy_unreachable_message),
                title = stringResource(Res.string.connection_proxy_failing_title),
                tone = UciTone.Danger,
                action = { UciButton(stringResource(Res.string.connection_test_proxy_action), actions.onTestProxy, variant = UciButtonVariant.Text) },
            )
            null -> ConnectedAlerts(tunnel)
        }
        else -> Unit
    }
}

/** El botón que arregla un fallo: editar el perfil, probar el proxy, ir a los ajustes del sistema o reintentar. */
@Composable
private fun FailureActionButton(action: FailureAction, actions: ConnectionActions, onEdit: () -> Unit) {
    val settingsPage = when (action) {
        FailureAction.OPEN_APP_SETTINGS -> SystemSettingsPage.APP
        FailureAction.OPEN_VPN_SETTINGS -> SystemSettingsPage.VPN
        else -> null
    }
    when {
        action == FailureAction.EDIT_PROFILE ->
            UciButton(stringResource(Res.string.connection_edit_profile), onEdit, variant = UciButtonVariant.Text)
        action == FailureAction.TEST_PROXY ->
            UciButton(stringResource(Res.string.connection_test_proxy_action), actions.onTestProxy, variant = UciButtonVariant.Text)
        settingsPage != null && actions.systemSettings.canOpen(settingsPage) -> UciButton(
            stringResource(if (settingsPage == SystemSettingsPage.APP) Res.string.failure_action_open_app_settings else Res.string.failure_action_open_vpn_settings),
            { actions.systemSettings.open(settingsPage) },
            variant = UciButtonVariant.Text,
        )
        // Reintentar, o donde no se puede llevar a los ajustes del sistema.
        else -> UciButton(stringResource(Res.string.connection_retry), actions.onConnect, variant = UciButtonVariant.Text)
    }
}

/** Los avisos de un túnel conectado con el proxy funcionando. */
@Composable
private fun ConnectedAlerts(tunnel: TunnelState.Connected) {
    when {
        tunnel.vpnDown -> UciInlineAlert(
            stringResource(if (tunnel.vpnBlocked) Res.string.connection_vpn_down_blocked_message else Res.string.connection_vpn_down_message),
            title = stringResource(Res.string.connection_vpn_down_title),
            tone = UciTone.Warning,
        )
        // Otro País está, pero su DNS no resolvió algunos nombres: esos salen por el proxy y hay que decirlo.
        tunnel.viaProxy.isNotEmpty() -> UciInlineAlert(
            viaProxyMessage(tunnel.viaProxy),
            title = stringResource(Res.string.connection_via_proxy_title),
            tone = UciTone.Warning,
        )
    }
}

@Composable
private fun ProxyCheckCard(state: ConnectionUiState, onTest: () -> Unit) {
    val check = state.check
    UciSectionCard(
        title = stringResource(Res.string.connection_check_title),
        subtitle = stringResource(Res.string.connection_check_subtitle),
        icon = UciIcons.Speed,
        trailing = {
            UciButton(
                stringResource(Res.string.connection_check_button),
                onTest,
                variant = UciButtonVariant.Secondary,
                loading = check == ProxyCheck.Running,
            )
        },
    ) {
        when (check) {
            ProxyCheck.Idle, ProxyCheck.Running -> Unit
            ProxyCheck.LocalNetworkDenied -> UciInlineAlert(stringResource(LocalNetworkDeniedForTest), tone = UciTone.Warning)
            is ProxyCheck.Finished -> when (check.result) {
                // El tiempo es el de la prueba entera (conectar, autenticarse, abrir el túnel), no una latencia: no se
                // le aplican los umbrales de latencia del diseño.
                ProxyTestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UciStatusPill(stringResource(Res.string.connection_check_ok, check.millis), tone = UciTone.Success, icon = UciIcons.Check)
                    Text(stringResource(Res.string.connection_check_success), style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
                }
                ProxyTestResult.Timeout, is ProxyTestResult.Failure -> Column {
                    UciInlineAlert(
                        check.result.message(),
                        tone = if (check.result == ProxyTestResult.Timeout) UciTone.Warning else UciTone.Danger,
                    )
                    check.result.code?.let { FailureDetails(it, check.result.technicalDetail) }
                }
            }
        }
    }
}

@Composable
private fun RoutingCard(summary: RoutingSummary?, onEdit: () -> Unit) {
    summary ?: return
    UciSectionCard(
        title = stringResource(Res.string.connection_routing_title),
        subtitle = stringResource(if (summary.enabled) Res.string.connection_routing_enabled else Res.string.connection_routing_disabled),
        icon = UciIcons.Split,
        tone = if (summary.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = { UciButton(stringResource(Res.string.connection_routing_edit), onEdit, variant = UciButtonVariant.Text) },
    ) {
        ExitRow(stringResource(if (summary.enabled) Res.string.connection_routing_default else Res.string.connection_routing_everything), summary.defaultExit)
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
        UciStatusPill(stringResource(exit.summaryName), tone = exit.tone)
    }
}

/** El nombre corto de cada salida en el resumen («Bloqueado», no el «Bloquear» de la regla). */
private val RouteExit.summaryName: StringResource
    get() = when (this) {
        RouteExit.PROXY -> Res.string.connection_exit_proxy
        RouteExit.VPN -> Res.string.connection_exit_country
        RouteExit.DIRECT -> Res.string.connection_exit_direct
        RouteExit.BLOCK -> Res.string.connection_exit_blocked
    }

/** Cuántas reglas hay de cada tipo, siempre en el mismo orden (sitios, redes, apps) y sin los tipos que no tienen. */
internal fun Map<RuleKind, Int>.inOrder(): List<Pair<RuleKind, Int>> =
    RuleKind.entries.mapNotNull { kind -> this[kind]?.let { kind to it } }

/** «2 sitios, 1 app» a partir de cuántas reglas hay de cada tipo. */
@Composable
private fun Map<RuleKind, Int>.describe(): String = inOrder().map { (kind, count) ->
    val plural = when (kind) {
        RuleKind.DOMAIN -> Res.plurals.connection_rules_domains
        RuleKind.IP -> Res.plurals.connection_rules_networks
        RuleKind.APP -> Res.plurals.connection_rules_apps
    }
    pluralStringResource(plural, count, count)
}.joinToString(", ")

@Composable
private fun RecentActivity(entries: List<LogEntry>, onOpenLogs: () -> Unit) {
    UciSectionHeader(stringResource(Res.string.connection_recent_activity), trailing = { UciButton(stringResource(Res.string.connection_see_all), onOpenLogs, variant = UciButtonVariant.Text) })
    UciCard(Modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            Text(stringResource(Res.string.connection_no_activity), style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) UciDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                UciStatusDot(entry.level.tone, modifier = Modifier.padding(top = 6.dp))
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

@Composable
private fun ProfilePicker(state: ConnectionUiState, onSelect: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    UciBottomSheet(stringResource(Res.string.connection_choose_profile), onDismiss = onDismiss) {
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
                        { UciStatusPill(stringResource(Res.string.connection_profile_active), tone = UciTone.Active) }
                    } else {
                        null
                    },
                )
            }
        }
        UciButton(stringResource(Res.string.connection_add_profile), onAdd, variant = UciButtonVariant.Secondary, icon = UciIcons.Add, modifier = Modifier.fillMaxWidth())
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
    ProxyUciTheme { ConnectionScreen(previewState.copy(tunnel = TunnelState.Failed(TunnelFailure.ProxyAuthFailed)), wide = false) }
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
