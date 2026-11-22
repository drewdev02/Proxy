package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.common.message
import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.logs.formatLogTime
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciStatusDot

@Composable
internal fun RecentActivity(entries: List<LogEntry>, onOpenLogs: () -> Unit) {
    UciSectionHeader("Actividad reciente", trailing = { UciButton("Ver todo", onOpenLogs, variant = UciButtonVariant.Text) })
    UciCard(Modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            Text("Sin actividad todavía.", style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
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
