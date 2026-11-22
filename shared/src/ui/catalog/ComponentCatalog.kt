package org.uci.proxy.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBottomNavBar
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciCheckboxRow
import org.uci.proxy.ui.components.UciConfirmDialog
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciFileField
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciIconBox
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciLatencyBadge
import org.uci.proxy.ui.components.UciLogEntryCard
import org.uci.proxy.ui.components.UciLogTag
import org.uci.proxy.ui.components.UciMetricTile
import org.uci.proxy.ui.components.UciQuotaRing
import org.uci.proxy.ui.components.UciProportionBar
import org.uci.proxy.ui.components.UciBarSegment
import org.uci.proxy.ui.components.UciBar
import org.uci.proxy.ui.components.UciBarChart
import org.uci.proxy.ui.components.UciNavItem
import org.uci.proxy.ui.components.UciNavigationRail
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciPowerButton
import org.uci.proxy.ui.components.UciPowerState
import org.uci.proxy.ui.components.UciProfileRow
import org.uci.proxy.ui.components.UciReorderableList
import org.uci.proxy.ui.components.UciSearchField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSettingRow
import org.uci.proxy.ui.components.UciSparkline
import org.uci.proxy.ui.components.UciStatusDot
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons

/*
 * Catálogo de componentes: todos los componentes en todos sus estados, con datos de ejemplo.
 * Solo se ofrece en modo desarrollo. Sirve para revisarlos a la vez en Android, macOS y Windows, y las pruebas
 * de capturas (CatalogSnapshotTest) dibujan cada sección para detectar cambios de aspecto no buscados.
 */

/** Una sección del catálogo. El orden es el de la pantalla. */
enum class CatalogSection(val title: StringResource, val content: @Composable ColumnScope.() -> Unit) {
    Foundations(Res.string.catalog_section_foundations, { FoundationsSection() }),
    Containers(Res.string.catalog_section_containers, { ContainersSection() }),
    Buttons(Res.string.catalog_section_buttons, { ButtonsSection() }),
    Power(Res.string.catalog_section_power, { PowerSection() }),
    Status(Res.string.catalog_section_status, { StatusSection() }),
    Forms(Res.string.catalog_section_forms, { FormsSection() }),
    Lists(Res.string.catalog_section_lists, { ListsSection() }),
    Navigation(Res.string.catalog_section_navigation, { NavigationSection() }),
    Data(Res.string.catalog_section_data, { DataSection() }),
}

@Composable
fun ComponentCatalogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            UciTopBar(stringResource(Res.string.catalog_title), subtitle = stringResource(Res.string.catalog_subtitle), onBack = onBack)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = ProxyUciTheme.spacing.screenMargin,
                vertical = ProxyUciTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.xl),
        ) {
            items(CatalogSection.entries, key = { it.name }) { CatalogSectionView(it) }
        }
    }
}

/** Una sección con su título. Se usa en la pantalla y en las pruebas de capturas. */
@Composable
fun CatalogSectionView(section: CatalogSection, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md)) {
        Text(stringResource(section.title), style = MaterialTheme.typography.headlineSmall)
        section.content(this)
    }
}

@Composable
private fun Sub(text: StringResource) = UciSectionHeader(stringResource(text))

/** Abreviatura de [stringResource]: el catálogo tiene muchos textos de ejemplo y así las líneas siguen siendo legibles. */
@Composable
private fun s(resource: StringResource): String = stringResource(resource)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundationsSection() {
    val c = ProxyUciTheme.colors
    val scheme = MaterialTheme.colorScheme
    Sub(Res.string.catalog_surfaces)
    Swatches(
        listOf(
            s(Res.string.catalog_swatch_background) to c.canvas, s(Res.string.catalog_swatch_card) to c.card,
            s(Res.string.catalog_swatch_border) to c.cardBorder, s(Res.string.catalog_swatch_control) to c.control,
            s(Res.string.catalog_swatch_control_border) to c.controlBorder,
        ),
    )
    Sub(Res.string.catalog_meaning)
    Swatches(
        listOf(
            s(Res.string.catalog_swatch_active) to c.active, s(Res.string.catalog_connected) to c.connected,
            s(Res.string.catalog_other_country) to c.routing, s(Res.string.catalog_swatch_idle) to c.idle,
            s(Res.string.catalog_swatch_warning) to c.warning, s(Res.string.catalog_error) to c.danger,
            s(Res.string.catalog_swatch_primary) to scheme.primary,
        ),
    )
    Sub(Res.string.catalog_typography)
    val t = MaterialTheme.typography
    val m = ProxyUciTheme.mono
    listOf<Pair<String, TextStyle>>(
        "Display 32" to t.displayMedium,
        "Headline 20" to t.headlineSmall,
        "Title 16" to t.titleMedium,
        s(Res.string.catalog_type_body) to t.bodyMedium,
        "Label 12" to t.labelMedium,
    ).forEach { (text, style) -> Text(text, style = style) }
    Text("4.82", style = m.metricLarge)
    Text("proxy.uci.cu:8080 · 38 ms", style = m.metricSmall)
    Text("10.0.0.0/8 → DIRECT", style = m.code)
    Text("NTLMV2 · TUNNEL", style = m.badge, color = c.onSurfaceMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
                Box(
                    Modifier.size(48.dp)
                        .background(color, MaterialTheme.shapes.small)
                        .border(1.dp, ProxyUciTheme.colors.controlBorder, MaterialTheme.shapes.small),
                )
                Text(name, style = MaterialTheme.typography.labelSmall, color = ProxyUciTheme.colors.onSurfaceMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContainersSection() {
    Sub(Res.string.catalog_cards_by_tone)
    UciCard(Modifier.fillMaxWidth()) { Text(s(Res.string.catalog_card_normal)) }
    UciCard(Modifier.fillMaxWidth(), tone = UciTone.Success) { Text(s(Res.string.catalog_connected)) }
    UciCard(Modifier.fillMaxWidth(), tone = UciTone.Routing, onClick = {}) { Text(s(Res.string.catalog_card_clickable)) }
    Sub(Res.string.catalog_section_card)
    var on by remember { mutableStateOf(true) }
    UciSectionCard(
        title = s(Res.string.catalog_other_country),
        subtitle = s(Res.string.catalog_other_country_subtitle),
        icon = UciIcons.Globe,
        tone = UciTone.Routing,
        trailing = { UciSwitch(on, { on = it }, contentDescription = s(Res.string.catalog_enable_other_country)) },
    ) {
        Text(
            s(Res.string.catalog_other_country_body),
            style = MaterialTheme.typography.bodyMedium,
            color = ProxyUciTheme.colors.onSurfaceMuted,
        )
    }
    Sub(Res.string.catalog_icon_box)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UciTone.entries.forEach { UciIconBox(UciIcons.Shield, tone = it) }
    }
}

@Composable
private fun ButtonsSection() {
    Sub(Res.string.catalog_variants)
    UciButtonVariant.entries.forEach { variant ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            UciButton(variant.name, {}, variant = variant, icon = UciIcons.Refresh)
            UciButton(s(Res.string.catalog_disabled), {}, variant = variant, enabled = false)
        }
    }
    Sub(Res.string.catalog_loading_full_width)
    UciButton(s(Res.string.catalog_testing_connection), {}, loading = true, modifier = Modifier.fillMaxWidth())
    UciButton(s(Res.string.catalog_save_changes), {}, icon = UciIcons.Check, modifier = Modifier.fillMaxWidth())
    Sub(Res.string.catalog_icon_only)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        UciIconButton(UciIcons.Swap, s(Res.string.catalog_switch_profile), {})
        UciIconButton(UciIcons.Copy, s(Res.string.catalog_copy), {}, tone = UciTone.Active)
        UciIconButton(UciIcons.Delete, s(Res.string.catalog_delete), {}, tone = UciTone.Danger)
        UciIconButton(UciIcons.Terminal, s(Res.string.catalog_logs), {}, bordered = false)
        UciIconButton(UciIcons.Edit, s(Res.string.catalog_edit), {}, enabled = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerSection() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            UciPowerState.Idle to Res.string.catalog_connect,
            UciPowerState.Connecting to Res.string.catalog_connecting,
            UciPowerState.Connected to Res.string.catalog_connected,
            UciPowerState.Error to Res.string.catalog_retry,
        ).forEach { (state, label) -> UciPowerButton(state, s(label), {}, diameter = 110.dp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSection() {
    Sub(Res.string.catalog_tags)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UciStatusPill("Direct")
        UciStatusPill("Tunnel", tone = UciTone.Active)
        UciStatusPill("NTLMv2", tone = UciTone.Success, icon = UciIcons.Lock)
        UciStatusPill(s(Res.string.catalog_other_country), tone = UciTone.Routing)
        UciStatusPill(s(Res.string.catalog_pill_isolated), tone = UciTone.Warning)
        UciStatusPill(s(Res.string.catalog_pill_failure), tone = UciTone.Danger)
        UciStatusPill("HTTP 200", tone = UciTone.Success, pill = false)
        UciStatusPill("DNS:INTERCEPT", tone = UciTone.Routing, pill = false)
    }
    Sub(Res.string.catalog_dots_latency)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        UciStatusDot(UciTone.Success)
        UciStatusDot(UciTone.Active, pulsing = true)
        UciStatusDot(UciTone.Neutral)
        UciStatusDot(UciTone.Danger)
        UciLatencyBadge(38)
        UciLatencyBadge(120)
        UciLatencyBadge(420)
        UciLatencyBadge(null)
    }
    Sub(Res.string.catalog_filters)
    val all = s(Res.string.catalog_filter_all)
    var selected by remember { mutableStateOf(all) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple(all, 142, null), Triple("DNS", 68, UciTone.Routing), Triple("Proxy", 45, UciTone.Active), Triple(s(Res.string.catalog_filter_errors), 3, UciTone.Danger))
            .forEach { (label, count, dot) -> UciFilterChip(label, selected == label, { selected = label }, count = count, dot = dot) }
    }
    Sub(Res.string.catalog_alerts)
    UciInlineAlert(s(Res.string.catalog_alert_info))
    UciInlineAlert(s(Res.string.catalog_alert_success), tone = UciTone.Success)
    UciInlineAlert(s(Res.string.catalog_alert_warning), tone = UciTone.Warning, title = s(Res.string.catalog_alert_warning_title), onDismiss = {})
    UciInlineAlert(
        s(Res.string.catalog_alert_error),
        tone = UciTone.Danger,
        title = s(Res.string.catalog_alert_error_title),
        action = { UciButton(s(Res.string.catalog_retry), {}, variant = UciButtonVariant.Text) },
    )
}

private enum class CatalogMode { Inclusive, Exclusive }

@Composable
private fun FormsSection() {
    var host by remember { mutableStateOf("proxy.uci.cu") }
    var port by remember { mutableStateOf("80a") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("secreto") }
    var query by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("NTLMv2") }
    var mode by remember { mutableStateOf(CatalogMode.Inclusive) }
    var s1 by remember { mutableStateOf(true) }
    var s2 by remember { mutableStateOf(false) }
    var cidr1 by remember { mutableStateOf(true) }
    var cidr2 by remember { mutableStateOf(false) }

    Sub(Res.string.catalog_fields)
    UciTextField(host, { host = it }, s(Res.string.catalog_server), leadingIcon = UciIcons.Dns, mono = true, supportingText = s(Res.string.catalog_server_hint))
    UciTextField(port, { port = it }, s(Res.string.catalog_port), mono = true, error = s(Res.string.catalog_port_error))
    UciTextField(user, { user = it }, s(Res.string.catalog_user), placeholder = s(Res.string.catalog_user_placeholder), leadingIcon = UciIcons.Person)
    UciPasswordField(pass, { pass = it }, s(Res.string.catalog_password), supportingText = s(Res.string.catalog_password_hint))
    UciTextField(s(Res.string.catalog_read_only), {}, s(Res.string.catalog_disabled), enabled = false)
    UciSearchField(query, { query = it }, s(Res.string.catalog_search_placeholder))
    Sub(Res.string.catalog_selector)
    UciSegmentedControl(listOf("NTLMv2", "Basic", s(Res.string.catalog_no_auth)), auth, { auth = it }, label = { it })
    val inclusive = s(Res.string.catalog_mode_inclusive)
    val exclusive = s(Res.string.catalog_mode_exclusive)
    val inclusiveDescription = s(Res.string.catalog_mode_inclusive_description)
    val exclusiveDescription = s(Res.string.catalog_mode_exclusive_description)
    UciSegmentedControl(
        CatalogMode.entries,
        mode,
        { mode = it },
        label = { if (it == CatalogMode.Inclusive) inclusive else exclusive },
        description = { if (it == CatalogMode.Inclusive) inclusiveDescription else exclusiveDescription },
    )
    Sub(Res.string.catalog_switches_checkboxes)
    UciCard(Modifier.fillMaxWidth()) {
        UciSwitchRow(
            s(Res.string.catalog_answer_network_checks), s1, { s1 = it },
            subtitle = s(Res.string.catalog_answer_network_checks_hint), icon = UciIcons.CheckCircle,
        )
        UciDivider()
        UciSwitchRow(s(Res.string.catalog_dns_leak_protection), s2, { s2 = it }, icon = UciIcons.Shield)
        UciDivider()
        UciSwitchRow(s(Res.string.catalog_unavailable_here), false, {}, enabled = false)
        UciDivider()
        UciCheckboxRow("10.0.0.0/8", cidr1, { cidr1 = it }, subtitle = s(Res.string.catalog_university_networks), mono = true)
        UciDivider()
        UciCheckboxRow("192.168.0.0/16", cidr2, { cidr2 = it }, subtitle = s(Res.string.catalog_home_networks), mono = true)
    }
    Sub(Res.string.catalog_file)
    UciFileField("canada_udp.ovpn", {}, detail = s(Res.string.catalog_file_detail))
    UciFileField(null, {})
}

@Composable
private fun ListsSection() {
    Sub(Res.string.catalog_setting_rows)
    UciCard(Modifier.fillMaxWidth()) {
        UciSettingRow(s(Res.string.catalog_name_services), subtitle = "sslip.io, nip.io", icon = UciIcons.Dns, onClick = {})
        UciDivider()
        UciSettingRow(s(Res.string.catalog_version), icon = UciIcons.Info) {
            Text("0.9.4", style = ProxyUciTheme.mono.metricSmall, color = ProxyUciTheme.colors.onSurfaceMuted)
        }
    }
    Sub(Res.string.catalog_profiles)
    UciProfileRow(
        name = "UCI Docente",
        address = "proxy.uci.cu:8080",
        status = UciTone.Success,
        tone = UciTone.Success,
        onClick = {},
        tags = {
            UciStatusPill("HTTP")
            UciStatusPill("NTLMv2", tone = UciTone.Success)
            UciStatusPill(s(Res.string.catalog_other_country), tone = UciTone.Routing)
        },
        trailing = { UciIconButton(UciIcons.Edit, s(Res.string.catalog_edit_profile), {}, bordered = false) },
    )
    UciProfileRow(
        name = "Residencia",
        address = "10.0.0.1:3128",
        status = UciTone.Active,
        statusPulsing = true,
        onClick = {},
        tags = { UciStatusPill("SOCKS5") },
    )
    Sub(Res.string.catalog_reorderable_list)
    val services = remember { mutableStateListOf("sslip.io", "nip.io", "propio.example.cu") }
    UciReorderableList(services, onMove = { from, to -> services.add(to, services.removeAt(from)) }) { name ->
        Text(name, style = ProxyUciTheme.mono.metricSmall, modifier = Modifier.weight(1f))
        UciLatencyBadge(if (name == "sslip.io") 42 else if (name == "nip.io") 58 else null)
    }
    Sub(Res.string.catalog_empty)
    UciCard(Modifier.fillMaxWidth()) {
        UciEmptyState(
            title = s(Res.string.catalog_empty_title),
            icon = UciIcons.Badge,
            message = s(Res.string.catalog_empty_message),
            action = { UciButton(s(Res.string.catalog_add_profile), {}, icon = UciIcons.Add) },
        )
    }
}

private enum class Dest { Connection, Logs, Profiles, Settings }

@Composable
private fun NavigationSection() {
    val items = listOf(
        UciNavItem(Dest.Connection, s(Res.string.nav_connection), UciIcons.Shield),
        UciNavItem(Dest.Logs, s(Res.string.nav_logs), UciIcons.Logs, badge = 12),
        UciNavItem(Dest.Profiles, s(Res.string.nav_profiles), UciIcons.Badge),
        UciNavItem(Dest.Settings, s(Res.string.nav_settings), UciIcons.Tune),
    )
    var dest by remember { mutableStateOf(Dest.Connection) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }

    Sub(Res.string.catalog_header)
    UciCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        UciTopBar(s(Res.string.catalog_edit_profile), subtitle = "UCI Docente", onBack = {}, actions = { UciStatusPill("Tunnel", tone = UciTone.Success) })
    }
    Sub(Res.string.catalog_bottom_bar)
    UciBottomNavBar(items, dest, { dest = it })
    Sub(Res.string.catalog_side_rail)
    Box(Modifier.height(320.dp).border(1.dp, ProxyUciTheme.colors.cardBorder, MaterialTheme.shapes.medium)) {
        UciNavigationRail(items, dest, { dest = it })
    }
    Sub(Res.string.catalog_dialog_sheet)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UciButton(s(Res.string.catalog_delete_profile), { dialog = true }, variant = UciButtonVariant.Danger)
        UciButton(s(Res.string.catalog_choose_country), { sheet = true }, variant = UciButtonVariant.Secondary)
    }
    if (dialog) {
        UciConfirmDialog(
            title = stringResource(Res.string.catalog_delete_profile_title, "UCI Docente"),
            message = s(Res.string.catalog_delete_profile_message),
            confirmLabel = s(Res.string.catalog_delete),
            destructive = true,
            onConfirm = { dialog = false },
            onDismiss = { dialog = false },
        )
    }
    if (sheet) {
        UciBottomSheet(s(Res.string.catalog_choose_country), onDismiss = { sheet = false }) {
            stringArrayResource(Res.array.catalog_countries).forEach { UciSettingRow(it, icon = UciIcons.Globe, onClick = { sheet = false }) }
        }
    }
}

@Composable
private fun DataSection() {
    Sub(Res.string.catalog_figures)
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
        UciMetricTile(
            s(Res.string.catalog_download), "4.8", Modifier.weight(1f), unit = "MB/s", icon = UciIcons.ArrowDown, accent = UciTone.Active,
            chart = { UciSparkline(listOf(1f, 3f, 2f, 5f, 4f, 6f, 4.8f)) },
        )
        UciMetricTile(
            s(Res.string.catalog_upload), "820", Modifier.weight(1f), unit = "KB/s", icon = UciIcons.ArrowUp, accent = UciTone.Success,
            chart = { UciSparkline(listOf(2f, 2f, 3f, 2.5f, 4f, 3f, 3.5f), color = ProxyUciTheme.colors.connected) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
        UciMetricTile(
            s(Res.string.catalog_connections), "34", Modifier.weight(1f), icon = UciIcons.Hub,
            caption = s(Res.string.catalog_connections_caption),
        )
        UciMetricTile(
            s(Res.string.catalog_session), "02:45", Modifier.weight(1f), unit = "h", icon = UciIcons.Schedule,
            caption = s(Res.string.catalog_session_caption),
        )
    }
    Sub(Res.string.catalog_usage)
    val colors = ProxyUciTheme.colors
    val days = stringArrayResource(Res.array.catalog_days)
    UciBarChart(
        bars = days.zip(listOf(3f, 5f, 4f, 1f, 0f, 4.5f, 3.2f)).mapIndexed { i, (day, value) ->
            UciBar(day, listOf(UciBarSegment(value * 0.7f, colors.active), UciBarSegment(value * 0.3f, colors.routing)), highlighted = i == 6)
        },
    )
    UciProportionBar(0.7f)
    UciProportionBar(0.3f, color = colors.routing)
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md)) {
        UciQuotaRing(0.71f, color = colors.connected) { Text("594 MB", style = ProxyUciTheme.mono.metricSmall) }
        UciQuotaRing(0.92f, color = colors.danger) { Text("158 MB", style = ProxyUciTheme.mono.metricSmall) }
    }
    Sub(Res.string.catalog_log)
    UciLogEntryCard(
        time = "14:32:05.124",
        title = "github.com:443",
        tags = listOf(UciLogTag("Relay:connect", UciTone.Active), UciLogTag("HTTP 200", UciTone.Success)),
        message = s(Res.string.catalog_log_tunnel_open),
        details = listOf(s(Res.string.catalog_log_route) to "10.0.0.45:52410 → proxy.uci.cu:8080", "Auth" to "NTLMv2"),
        trailing = "32 ms",
        trailingTone = UciTone.Success,
        icon = UciIcons.Lock,
    )
    UciLogEntryCard(
        time = "14:31:58.112",
        title = "198-51-100-24.sslip.io",
        tags = listOf(UciLogTag("DNS:resolver", UciTone.Routing), UciLogTag("Fallback", UciTone.Warning)),
        message = s(Res.string.catalog_log_resolved),
        icon = UciIcons.Dns,
    )
    UciLogEntryCard(
        time = "14:31:45.890",
        title = "proxy.uci.cu:8080",
        tags = listOf(UciLogTag("Proxy:auth", UciTone.Danger)),
        message = s(Res.string.catalog_log_auth_rejected),
        trailing = "ERROR",
        trailingTone = UciTone.Danger,
        icon = UciIcons.Error,
    )
}
