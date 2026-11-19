package org.uci.proxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.theme.ProxyUciTheme

/**
 * Tarjeta del diseño: fondo de nivel 1 y borde de 1 px. Con [tone] distinto de [UciTone.Neutral] el borde toma
 * el color del tono, para marcar por ejemplo el perfil conectado o una ruta de Otro País.
 * Con [onClick] toda la tarjeta se puede pulsar.
 */
@Composable
fun UciCard(
    modifier: Modifier = Modifier,
    tone: UciTone = UciTone.Neutral,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(ProxyUciTheme.spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ProxyUciTheme.colors
    val border = BorderStroke(1.dp, if (tone == UciTone.Neutral) colors.cardBorder else tone.colors().border)
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall),
            content = content,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = colors.card,
            border = border,
            content = inner,
        )
    } else {
        Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = colors.card, border = border, content = inner)
    }
}

/**
 * Tarjeta con cabecera: icono en una caja, título, subtítulo y algo a la derecha (un interruptor, una etiqueta).
 * Es la forma de agrupar ajustes relacionados, como «Otro País» o «Servicios de nombres».
 */
@Composable
fun UciSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    tone: UciTone = UciTone.Neutral,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    UciCard(modifier = modifier, tone = tone) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
            if (icon != null) UciIconBox(icon = icon, tone = tone)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProxyUciTheme.colors.onSurfaceMuted)
                }
            }
            trailing?.invoke(this)
        }
        content?.invoke(this)
    }
}

/** Icono dentro de una caja redondeada, como el que encabeza tarjetas y filas. */
@Composable
fun UciIconBox(icon: ImageVector, modifier: Modifier = Modifier, tone: UciTone = UciTone.Neutral, size: Int = 40) {
    val colors = tone.colors()
    Box(
        modifier = modifier
            .size(size.dp)
            .background(if (tone == UciTone.Neutral) ProxyUciTheme.colors.control else colors.container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (tone == UciTone.Neutral) MaterialTheme.colorScheme.primary else colors.content,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

/**
 * Rótulo de una sección de pantalla, en mayúsculas y monoespaciada, con algo opcional a la derecha
 * (un enlace o un estado como «Sincronizado»).
 */
@Composable
fun UciSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ProxyUciTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = ProxyUciTheme.mono.badge,
            color = ProxyUciTheme.colors.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

/** Línea de separación entre filas de una tarjeta. */
@Composable
fun UciDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = ProxyUciTheme.colors.cardBorder)
}
