package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
) {
    val rules = routing.rules
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(rule.value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rule.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        OutlinedButton(onClick = actions::addRule, enabled = enabled && routing.newRule.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Añadir regla")
        }
        Text(
            "Un dominio incluye sus subdominios. «Directo» (sin proxy) solo vale para IP y rangos. " +
                "Si Otro País se cae, su tráfico sale por el proxy hasta que vuelva.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
