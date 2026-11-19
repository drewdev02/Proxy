package org.uci.proxy.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.InstalledApp
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciIconBox
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciSearchField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSettingRow
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

/**
 * «Reglas de salida»: qué sitios y apps salen por el proxy, por Otro País, directo o se bloquean. La primera regla que
 * coincide gana; lo demás toma la salida por defecto.
 */
@Composable
internal fun RoutingSection(
    routing: RoutingDraft,
    /** Si el perfil tiene Otro País activado: sin él, esa salida no se ofrece. */
    vpnEnabled: Boolean,
    enabled: Boolean,
    actions: RoutingActions,
    /** Las apps instaladas, para las reglas por app; null donde no se aplican. */
    appCatalog: AppCatalog? = null,
) {
    val rules = routing.rules
    var pickingApp by remember { mutableStateOf(false) }
    val hasAppRules = rules.rules.any { it.kind == RuleKind.APP }
    // Las apps se cargan solo si hacen falta: para mostrar las reglas de app o para elegir una.
    val installed by produceState<List<InstalledApp>?>(null, appCatalog, hasAppRules || pickingApp) {
        if (appCatalog != null && (hasAppRules || pickingApp) && value == null) value = appCatalog.installedApps()
    }
    val appsByPackage = remember(installed) { installed.orEmpty().associateBy { it.packageName } }

    UciSectionCard(
        title = "Reglas de salida",
        subtitle = "Qué sale por el proxy, por Otro País, directo o se bloquea",
        icon = UciIcons.Split,
        tone = if (rules.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = { UciSwitch(rules.enabled, actions::onRoutingEnabledChange, enabled = enabled, contentDescription = "Activar reglas de salida") },
    ) {
        if (!rules.enabled) return@UciSectionCard

        if (!vpnEnabled && rules.usesVpn) {
            UciInlineAlert(
                "Activa «Otro País» para usar esa salida; mientras tanto, lo suyo sale por el proxy.",
                tone = UciTone.Warning,
            )
        }

        // «Otro País» como salida por defecto solo si está activado (o si ya estaba elegido, para poder quitarlo).
        val defaults = RoutingRules.DEFAULT_EXITS.filter { it != RouteExit.VPN || vpnEnabled || rules.defaultExit == it }
        if (defaults.size > 1) {
            Text("Lo que no coincida con ninguna regla sale por", style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
            UciSegmentedControl(
                options = defaults,
                selected = rules.defaultExit,
                onSelect = actions::onDefaultExitChange,
                label = { it.label },
                enabled = enabled,
            )
        } else {
            // Con una sola salida posible no hay nada que elegir: se dice cuál es y cómo tener más.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Lo que no coincida con ninguna regla sale por",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                UciStatusPill(rules.defaultExit.label, tone = rules.defaultExit.tone)
            }
            Text(
                "Activa «Otro País» para poder elegirlo también como salida.",
                style = MaterialTheme.typography.bodySmall,
                color = ProxyUciTheme.colors.onSurfaceFaint,
            )
        }

        UciSectionHeader(if (rules.rules.isEmpty()) "Sin reglas" else "Reglas · gana la primera que coincide")
        rules.rules.forEachIndexed { index, rule ->
            if (index > 0) UciDivider()
            RuleRow(
                rule = rule,
                app = if (rule.kind == RuleKind.APP) appsByPackage[rule.value] else null,
                canMoveUp = index > 0,
                vpnEnabled = vpnEnabled,
                enabled = enabled,
                onExitChange = { actions.onRuleExitChange(index, it) },
                onMoveUp = { actions.moveRule(index, -1) },
                onRemove = { actions.removeRule(index) },
            )
        }

        UciDivider()
        NewRule(routing, vpnEnabled, enabled, actions, canPickApp = appCatalog != null, onPickApp = { pickingApp = true })
        Text(
            "Un dominio incluye sus subdominios. «Directo» (sin proxy) vale para apps, IP y rangos, no para dominios. " +
                "Una app que sale por Otro País puede tardar un segundo más en su primera conexión. " +
                "Si Otro País se cae, su tráfico sale por el proxy hasta que vuelva.",
            style = MaterialTheme.typography.bodySmall,
            color = ProxyUciTheme.colors.onSurfaceFaint,
        )
    }

    if (pickingApp) {
        AppPickerSheet(
            apps = installed,
            exit = routing.newRuleExit,
            onPick = { packageName ->
                pickingApp = false
                actions.addAppRule(packageName)
            },
            onDismiss = { pickingApp = false },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RouteRule,
    app: InstalledApp?,
    canMoveUp: Boolean,
    vpnEnabled: Boolean,
    enabled: Boolean,
    onExitChange: (RouteExit) -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
) {
    // Dos líneas: arriba qué es la regla, a lo ancho para que no se corte en móvil; abajo su salida y los controles.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (app?.icon != null) AppIcon(app.icon) else UciIconBox(rule.kind.icon, size = 36)
            Column(Modifier.weight(1f)) {
                Text(
                    app?.label ?: rule.value,
                    style = if (app != null) MaterialTheme.typography.bodyLarge else ProxyUciTheme.mono.metricSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rule.kind == RuleKind.APP) "App · ${rule.value}" else rule.kind.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = ProxyUciTheme.colors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 46.dp)) {
            ExitPicker(rule.exit, RoutingRules.exitsFor(rule.kind), vpnEnabled, enabled, onExitChange)
            Spacer(Modifier.weight(1f))
            UciIconButton(UciIcons.ArrowUp, "Subir regla", onMoveUp, bordered = false, enabled = enabled && canMoveUp)
            UciIconButton(UciIcons.Delete, "Quitar regla", onRemove, bordered = false, tone = UciTone.Danger, enabled = enabled)
        }
    }
}

@Composable
private fun NewRule(
    routing: RoutingDraft,
    vpnEnabled: Boolean,
    enabled: Boolean,
    actions: RoutingActions,
    canPickApp: Boolean,
    onPickApp: () -> Unit,
) {
    UciTextField(
        value = routing.newRule,
        onValueChange = actions::onNewRuleChange,
        label = "Nueva regla: dominio, IP o rango",
        placeholder = "netflix.com, *.google.*, 1.2.3.0/24",
        mono = true,
        error = "Escribe un dominio (netflix.com), una IP o un rango (1.2.3.0/24).".takeIf { routing.newRuleInvalid },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.addRule() }),
        trailing = { ExitPicker(routing.newRuleExit, RouteExit.entries, vpnEnabled, enabled, actions::onNewRuleExitChange, Modifier.padding(end = 8.dp)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        UciButton(
            "Añadir regla",
            actions::addRule,
            variant = UciButtonVariant.Secondary,
            icon = UciIcons.Add,
            enabled = enabled && routing.newRule.isNotBlank(),
            modifier = Modifier.weight(1f),
        )
        if (canPickApp) {
            UciButton("Elegir app", onPickApp, variant = UciButtonVariant.Secondary, icon = UciIcons.Badge, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }
}

/** La salida de una regla como etiqueta de color; al pulsarla se elige otra. */
@Composable
private fun ExitPicker(
    selected: RouteExit,
    options: List<RouteExit>,
    vpnEnabled: Boolean,
    enabled: Boolean,
    onSelect: (RouteExit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        UciStatusPill(
            "${selected.label} ▾",
            tone = selected.tone,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = enabled) { open = true }
                .semantics {
                    role = Role.DropdownList
                    contentDescription = "Salida: ${selected.label}"
                },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = ProxyUciTheme.colors.card) {
            options.forEach { exit ->
                DropdownMenuItem(
                    text = { Text(exit.label) },
                    enabled = exit != RouteExit.VPN || vpnEnabled,
                    onClick = {
                        open = false
                        onSelect(exit)
                    },
                )
            }
        }
    }
}

/** Elige una app instalada; la regla toma la salida elegida para la regla nueva. */
@Composable
private fun AppPickerSheet(
    apps: List<InstalledApp>?,
    exit: RouteExit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    UciBottomSheet("App que sale por ${exit.label}", onDismiss = onDismiss) {
        UciSearchField(query, { query = it }, "Buscar por nombre o paquete")
        if (apps == null) {
            Text("Cargando apps…", style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        } else {
            val filtered = apps.filter {
                query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                UciEmptyState(title = "Ninguna app coincide", icon = UciIcons.Search)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    UciSettingRow(
                        title = app.label,
                        subtitle = app.packageName,
                        onClick = { onPick(app.packageName) },
                        trailing = { app.icon?.let { AppIcon(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap) {
    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
}

internal val RouteExit.label: String
    get() = when (this) {
        RouteExit.PROXY -> "Proxy"
        RouteExit.VPN -> "Otro País"
        RouteExit.DIRECT -> "Directo"
        RouteExit.BLOCK -> "Bloquear"
    }

/** El color de cada salida, el mismo en toda la app. */
internal val RouteExit.tone: UciTone
    get() = when (this) {
        RouteExit.PROXY -> UciTone.Active
        RouteExit.VPN -> UciTone.Routing
        RouteExit.DIRECT -> UciTone.Neutral
        RouteExit.BLOCK -> UciTone.Danger
    }

private val RuleKind.label: String
    get() = when (this) {
        RuleKind.DOMAIN -> "Dominio y subdominios"
        RuleKind.IP -> "IP o rango"
        RuleKind.APP -> "App"
    }

private val RuleKind.icon: ImageVector
    @Composable get() = when (this) {
        RuleKind.DOMAIN -> UciIcons.Globe
        RuleKind.IP -> UciIcons.Dns
        RuleKind.APP -> UciIcons.Badge
    }
