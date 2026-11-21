package org.uci.proxy.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.apps.AppCatalog
import org.uci.proxy.apps.InstalledApp
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind

/**
 * «Reglas de salida»: qué sitios salen por el proxy, por Otro País, directo o se bloquean. La primera regla que coincide
 * gana; lo demás toma la salida por defecto.
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = rules.enabled, role = Role.Switch, onValueChange = actions::onRoutingEnabledChange),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Reglas de salida", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Elige qué sitios salen por el proxy, por Otro País, directo o se bloquean.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rules.enabled, onCheckedChange = null)
        }
        if (!rules.enabled) return@Column

        if (!vpnEnabled && rules.usesVpn) {
            Text(
                "Activa «Salir por otro país» para usar esa salida; mientras tanto, lo suyo sale por el proxy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text("Lo que no coincida con ninguna regla sale por", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingRules.DEFAULT_EXITS.forEach { exit ->
                FilterChip(
                    selected = rules.defaultExit == exit,
                    onClick = { actions.onDefaultExitChange(exit) },
                    enabled = enabled && (exit != RouteExit.VPN || vpnEnabled || rules.defaultExit == exit),
                    label = { Text(exit.label) },
                )
            }
        }

        if (rules.rules.isNotEmpty()) {
            Text("Reglas (gana la primera que coincide)", style = MaterialTheme.typography.labelLarge)
        }
        rules.rules.forEachIndexed { index, rule ->
            val installedApp = if (rule.kind == RuleKind.APP) appsByPackage[rule.value] else null
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                installedApp?.icon?.let { AppIcon(it) }
                Column(Modifier.weight(1f)) {
                    Text(installedApp?.label ?: rule.value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (rule.kind == RuleKind.APP) "App · ${rule.value}" else rule.kind.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ExitPicker(
                    selected = rule.exit,
                    options = RoutingRules.exitsFor(rule.kind),
                    vpnEnabled = vpnEnabled,
                    enabled = enabled,
                    onSelect = { actions.onRuleExitChange(index, it) },
                )
                TextButton(onClick = { actions.moveRule(index, -1) }, enabled = enabled && index > 0) { Text("↑") }
                TextButton(onClick = { actions.removeRule(index) }, enabled = enabled) { Text("✕") }
            }
            HorizontalDivider()
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = routing.newRule,
                onValueChange = actions::onNewRuleChange,
                label = { Text("Dominio, IP o rango") },
                placeholder = { Text("netflix.com, *.google.*, 1.2.3.0/24") },
                singleLine = true,
                isError = routing.newRuleInvalid,
                supportingText = if (routing.newRuleInvalid) {
                    { Text("Escribe un dominio (ej. netflix.com), una IP o un rango (ej. 1.2.3.0/24).") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.addRule() }),
                modifier = Modifier.weight(1f),
            )
            ExitPicker(
                selected = routing.newRuleExit,
                options = RouteExit.entries,
                vpnEnabled = vpnEnabled,
                enabled = enabled,
                onSelect = actions::onNewRuleExitChange,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = actions::addRule, enabled = enabled && routing.newRule.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Añadir regla")
            }
            if (appCatalog != null) {
                OutlinedButton(onClick = { pickingApp = true }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Elegir app")
                }
            }
        }
        Text(
            "Un dominio incluye sus subdominios. «Directo» sale sin proxy: en un dominio, solo si su dirección es de la " +
                "red local (si es pública, sale por el proxy). " +
                "Una app que sale por Otro País puede tardar un segundo más en su primera conexión. " +
                "Si Otro País se cae, su tráfico sale por el proxy hasta que vuelva.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickingApp) {
        AppPickerDialog(
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

/** Elige una app instalada; la regla toma la salida elegida para la regla nueva. */
@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>?,
    exit: RouteExit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App que sale por ${exit.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (apps == null) {
                    Text("Cargando apps…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val filtered = apps.filter {
                        query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true)
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app.packageName) }
                                    .padding(vertical = 8.dp),
                            ) {
                                app.icon?.let { AppIcon(it) }
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap) {
    Image(bitmap = icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(32.dp))
}

/** Un botón con la salida elegida que abre la lista de las posibles. */
@Composable
private fun ExitPicker(
    selected: RouteExit,
    options: List<RouteExit>,
    vpnEnabled: Boolean,
    enabled: Boolean,
    onSelect: (RouteExit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(selected.label + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

internal val RouteExit.label: String
    get() = when (this) {
        RouteExit.PROXY -> "Proxy"
        RouteExit.VPN -> "Otro País"
        RouteExit.DIRECT -> "Directo"
        RouteExit.BLOCK -> "Bloquear"
    }

private val RuleKind.label: String
    get() = when (this) {
        RuleKind.DOMAIN -> "Dominio"
        RuleKind.IP -> "IP o rango"
        RuleKind.APP -> "App"
    }
