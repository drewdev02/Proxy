package org.uci.proxy.connection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

@Composable
internal fun RoutingCard(summary: RoutingSummary?, onEdit: () -> Unit) {
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
