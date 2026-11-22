package org.uci.proxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme

enum class UciButtonVariant {
    /** La acción principal de la pantalla: una sola por pantalla. */
    Primary,

    /** Acciones secundarias: con borde y sin relleno. */
    Secondary,

    /** Acciones de poca importancia, sin borde. */
    Text,

    /** Acciones destructivas: borrar, desconectar a la fuerza. */
    Danger,
}

/**
 * Botón de la app. Con [loading] muestra un indicador en lugar del icono y no se puede pulsar, pero mantiene su
 * tamaño para que la pantalla no salte mientras se prueba o se guarda.
 */
@Composable
fun UciButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UciButtonVariant = UciButtonVariant.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = ProxyUciTheme.colors
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    val clickable = enabled && !loading
    val loadingText = stringResource(Res.string.ui_loading)
    val loadingModifier = if (loading) Modifier.semantics { stateDescription = loadingText } else Modifier
    val minSize = Modifier.defaultMinSize(minHeight = 48.dp)

    val content: @Composable (Color) -> Unit = { tint ->
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
            Spacer(Modifier.width(ProxyUciTheme.spacing.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ProxyUciTheme.spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

    when (variant) {
        UciButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = modifier.then(minSize).then(loadingModifier),
            enabled = clickable,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = colors.control,
                disabledContentColor = colors.onSurfaceFaint,
            ),
        ) { content(if (clickable) MaterialTheme.colorScheme.onPrimary else colors.onSurfaceFaint) }

        UciButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(minSize).then(loadingModifier),
            enabled = clickable,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.dp, colors.controlBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.control,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = colors.onSurfaceFaint,
            ),
        ) { content(MaterialTheme.colorScheme.onSurface) }

        UciButtonVariant.Text -> TextButton(
            onClick = onClick,
            modifier = modifier.then(minSize).then(loadingModifier),
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) { content(MaterialTheme.colorScheme.primary) }

        UciButtonVariant.Danger -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(minSize).then(loadingModifier),
            enabled = clickable,
            shape = shape,
            contentPadding = padding,
            border = BorderStroke(1.dp, colors.danger.copy(alpha = if (clickable) 0.5f else 0.2f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.danger.copy(alpha = 0.08f),
                contentColor = colors.danger,
                disabledContentColor = colors.danger.copy(alpha = 0.4f),
            ),
        ) { content(colors.danger) }
    }
}

/**
 * Botón de solo icono. Con [bordered] es la caja cuadrada con borde de las cabeceras del diseño; sin él, un icono
 * suelto. [contentDescription] es obligatorio: es lo único que leen los lectores de pantalla.
 */
@Composable
fun UciIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bordered: Boolean = true,
    tone: UciTone = UciTone.Neutral,
    enabled: Boolean = true,
) {
    val colors = ProxyUciTheme.colors
    val tint = if (tone == UciTone.Neutral) MaterialTheme.colorScheme.onSurface else tone.colors().content
    if (bordered) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = modifier.size(44.dp),
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, colors.controlBorder),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = colors.control, contentColor = tint),
        ) { Icon(icon, contentDescription, Modifier.size(20.dp)) }
    } else {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Box(Modifier.alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription, Modifier.size(22.dp), tint = tint)
            }
        }
    }
}
