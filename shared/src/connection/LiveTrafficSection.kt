package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.uci.proxy.common.bytesText
import org.uci.proxy.common.formatBytes
import org.uci.proxy.common.formatRate
import org.uci.proxy.core.traffic.LiveTraffic
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciMetricTile
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciSparkline
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

/**
 * «Tráfico en vivo»: velocidades del último minuto, conexiones abiertas por el proxy y lo transferido en la sesión. Todo
 * medido en el motor; lo que sale directo no pasa por él y no cuenta. La tarjeta de la sesión abre el consumo guardado.
 */
@Composable
internal fun LiveTrafficSection(traffic: LiveTraffic, onOpenUsage: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = ProxyUciTheme.spacing
    val colors = ProxyUciTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        UciSectionHeader("Tráfico en vivo", trailing = { UciButton("Consumo", onOpenUsage, variant = UciButtonVariant.Text) })
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.gutterSmall)) {
            val (down, downUnit) = formatRate(traffic.downRate)
            UciMetricTile(
                label = "Descarga",
                value = down,
                unit = downUnit,
                icon = UciIcons.ArrowDown,
                accent = UciTone.Active,
                modifier = Modifier.weight(1f),
                chart = {
                    UciSparkline(
                        traffic.downRates.map { it.toFloat() },
                        color = colors.active,
                        baseline = 0f,
                        description = "Velocidad de descarga del último minuto",
                    )
                },
            )
            val (up, upUnit) = formatRate(traffic.upRate)
            UciMetricTile(
                label = "Subida",
                value = up,
                unit = upUnit,
                icon = UciIcons.ArrowUp,
                accent = UciTone.Success,
                modifier = Modifier.weight(1f),
                chart = {
                    UciSparkline(
                        traffic.upRates.map { it.toFloat() },
                        color = colors.connected,
                        baseline = 0f,
                        description = "Velocidad de subida del último minuto",
                    )
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.gutterSmall)) {
            UciMetricTile(
                label = "Conexiones",
                value = traffic.connections.toString(),
                icon = UciIcons.Hub,
                caption = "abiertas por el proxy",
                modifier = Modifier.weight(1f),
            )
            val (total, totalUnit) = formatBytes(traffic.sessionTotal)
            UciMetricTile(
                label = "Sesión",
                value = total,
                unit = totalUnit,
                icon = UciIcons.DataUsage,
                caption = "↓ ${bytesText(traffic.sessionDown)} · ↑ ${bytesText(traffic.sessionUp)}",
                onClick = onOpenUsage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
