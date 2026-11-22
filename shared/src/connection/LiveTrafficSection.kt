package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.bytesText
import org.uci.proxy.common.decimalSeparator
import org.uci.proxy.common.formatBytes
import org.uci.proxy.common.formatRate
import org.uci.proxy.core.traffic.LiveTraffic
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
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
    val separator = decimalSeparator()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        UciSectionHeader(stringResource(Res.string.connection_live_title), trailing = { UciButton(stringResource(Res.string.connection_live_usage), onOpenUsage, variant = UciButtonVariant.Text) })
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.gutterSmall)) {
            val (down, downUnit) = formatRate(traffic.downRate, separator)
            UciMetricTile(
                label = stringResource(Res.string.connection_live_download),
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
                        description = stringResource(Res.string.connection_live_download_chart),
                    )
                },
            )
            val (up, upUnit) = formatRate(traffic.upRate, separator)
            UciMetricTile(
                label = stringResource(Res.string.connection_live_upload),
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
                        description = stringResource(Res.string.connection_live_upload_chart),
                    )
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.gutterSmall)) {
            UciMetricTile(
                label = stringResource(Res.string.connection_live_connections),
                value = traffic.connections.toString(),
                icon = UciIcons.Hub,
                caption = stringResource(Res.string.connection_live_connections_caption),
                modifier = Modifier.weight(1f),
            )
            val (total, totalUnit) = formatBytes(traffic.sessionTotal, separator)
            UciMetricTile(
                label = stringResource(Res.string.connection_live_session),
                value = total,
                unit = totalUnit,
                icon = UciIcons.DataUsage,
                caption = "↓ ${bytesText(traffic.sessionDown, separator)} · ↑ ${bytesText(traffic.sessionUp, separator)}",
                onClick = onOpenUsage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
