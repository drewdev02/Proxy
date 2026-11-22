package org.uci.proxy.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.icons.UciIcons

/**
 * Etiqueta corta de estado o protocolo en mayúsculas y monoespaciada: DIRECT, PROXY, TUNNEL, NTLMv2.
 * Con [pill] es una píldora (metadatos); sin él, un rectángulo de esquinas cerradas (aspecto más técnico).
 */
@Composable
fun UciStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: UciTone = UciTone.Neutral,
    icon: ImageVector? = null,
    pill: Boolean = true,
) {
    val colors = tone.colors()
    Row(
        modifier = modifier
            .background(colors.container, if (pill) CircleShape else MaterialTheme.shapes.extraSmall)
            .border(1.dp, colors.border, if (pill) CircleShape else MaterialTheme.shapes.extraSmall)
            .padding(horizontal = if (pill) 8.dp else 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        Text(text.uppercase(), style = ProxyUciTheme.mono.badge, color = colors.content, maxLines = 1)
    }
}

/** Punto de color que indica un estado. Con [pulsing] late suavemente, para lo que está en marcha. */
@Composable
fun UciStatusDot(tone: UciTone, modifier: Modifier = Modifier, pulsing: Boolean = false, size: Int = 8) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
        value
    } else {
        1f
    }
    Box(modifier.size(size.dp).alpha(alpha).background(tone.colors().content, CircleShape))
}

/**
 * Latencia medida, con el color de su tramo (verde, ámbar o rojo). [millis] null significa sin medir.
 */
@Composable
fun UciLatencyBadge(millis: Long?, modifier: Modifier = Modifier) {
    val colors = ProxyUciTheme.colors
    val color = millis?.let { colors.latency(it) } ?: colors.onSurfaceFaint
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            if (millis == null) "— ms" else "$millis ms",
            style = ProxyUciTheme.mono.metricSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Filtro que se activa y desactiva, con un contador opcional («DNS 68») y un punto de color para su tipo.
 */
@Composable
fun UciFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    dot: UciTone? = null,
) {
    val colors = ProxyUciTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        leadingIcon = dot?.let { { UciStatusDot(it, size = 6) } },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (count != null) {
                    Text(
                        count.toString(),
                        style = ProxyUciTheme.mono.badge,
                        modifier = Modifier
                            .background(if (selected) Color.Black.copy(alpha = 0.2f) else colors.control, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.card,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.cardBorder,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * Aviso dentro de la pantalla. Se anuncia a los lectores de pantalla al aparecer.
 * [action] va a la derecha (por ejemplo un botón «Reintentar»); [onDismiss] añade una X para cerrarlo.
 */
@Composable
fun UciInlineAlert(
    message: String,
    modifier: Modifier = Modifier,
    tone: UciTone = UciTone.Neutral,
    title: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = tone.colors()
    val icon = when (tone) {
        UciTone.Success -> UciIcons.CheckCircle
        UciTone.Warning -> UciIcons.Warning
        UciTone.Danger -> UciIcons.Error
        else -> UciIcons.Info
    }
    val content = if (tone == UciTone.Neutral) MaterialTheme.colorScheme.primary else colors.content
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (onDismiss != null) 0.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = content)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            action?.invoke(this)
            if (onDismiss != null) {
                UciIconButton(UciIcons.Close, stringResource(Res.string.ui_alert_dismiss), onDismiss, bordered = false, modifier = Modifier.align(Alignment.Top))
            }
        }
    }
}
