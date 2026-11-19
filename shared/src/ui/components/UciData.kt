package org.uci.proxy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.theme.ProxyUciTheme

/**
 * Tarjeta con una cifra grande y su unidad («4.8 MB/s», «34 conexiones»). Las cifras van en monoespaciada para
 * que no muevan la tarjeta al cambiar. [chart] es un hueco opcional bajo la cifra, para una [UciSparkline].
 */
@Composable
fun UciMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    caption: String? = null,
    accent: UciTone = UciTone.Neutral,
    chart: (@Composable () -> Unit)? = null,
) {
    val c = ProxyUciTheme.colors
    UciCard(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (accent == UciTone.Neutral) c.onSurfaceMuted else accent.colors().content, modifier = Modifier.size(18.dp))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = ProxyUciTheme.mono.metricLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit != null) Text(unit, style = ProxyUciTheme.mono.badge, color = c.onSurfaceMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        chart?.invoke()
        if (caption != null) {
            Text(caption, style = ProxyUciTheme.mono.badge, color = if (accent == UciTone.Neutral) c.onSurfaceFaint else accent.colors().content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Gráfica de línea pequeña, sin ejes, con un degradado suave debajo. Con menos de dos valores no dibuja nada.
 * Solo para datos medidos de verdad: no rellenar con valores inventados.
 */
@Composable
fun UciSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = ProxyUciTheme.colors.active,
    description: String? = null,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { if (description != null) contentDescription = description },
    ) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val stroke = 2.dp.toPx()
        fun y(v: Float) = stroke + (size.height - 2 * stroke) * (1f - (v - min) / range)

        val line = Path().apply {
            moveTo(0f, y(values[0]))
            for (i in 1 until values.size) {
                // Curva suave entre puntos: control a mitad de camino en horizontal.
                val x0 = (i - 1) * stepX
                val x1 = i * stepX
                val mid = (x0 + x1) / 2
                cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, stroke * 1.4f, Offset(size.width, y(values.last())))
    }
}

/** Una etiqueta de un evento del registro: texto y tono. */
@Immutable
data class UciLogTag(val text: String, val tone: UciTone = UciTone.Neutral)

/**
 * Tarjeta de un evento del registro en vivo: hora, etiquetas, título (destino), explicación y pares de detalle
 * («Ruta» → «tun0 → proxy.uci.cu:8080»). [trailing] es un valor corto a la derecha de la hora (latencia, TTL).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UciLogEntryCard(
    time: String,
    title: String,
    modifier: Modifier = Modifier,
    tags: List<UciLogTag> = emptyList(),
    message: String? = null,
    details: List<Pair<String, String>> = emptyList(),
    trailing: String? = null,
    trailingTone: UciTone = UciTone.Neutral,
    icon: ImageVector? = null,
) {
    val c = ProxyUciTheme.colors
    UciCard(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = ProxyUciTheme.mono.metricSmall, color = c.onSurfaceMuted)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { UciStatusPill(it.text, tone = it.tone, pill = false) }
            }
            if (trailing != null) {
                Text(trailing, style = ProxyUciTheme.mono.badge, color = if (trailingTone == UciTone.Neutral) c.onSurfaceFaint else trailingTone.colors().content)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = c.onSurfaceMuted, modifier = Modifier.size(20.dp).padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = ProxyUciTheme.mono.metricSmall.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = MaterialTheme.colorScheme.onSurface)
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
            }
        }
        if (details.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(c.canvas, MaterialTheme.shapes.small).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                details.forEach { (key, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(key, style = ProxyUciTheme.mono.code, color = c.onSurfaceMuted)
                        Text(value, style = ProxyUciTheme.mono.code, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
