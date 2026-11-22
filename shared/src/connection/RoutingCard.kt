package org.uci.proxy.connection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
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
        UciStatusPill(exit.label(), tone = exit.tone)
    }
}

private fun RouteExit.label(): String = when (this) {
    RouteExit.PROXY -> "Proxy"
    RouteExit.VPN -> "Otro País"
    RouteExit.DIRECT -> "Directo"
    RouteExit.BLOCK -> "Bloqueado"
}
