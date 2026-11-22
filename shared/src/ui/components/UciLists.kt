package org.uci.proxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.icons.UciIcons

/**
 * Fila de ajuste: icono, título, subtítulo y algo a la derecha ([trailing]: un interruptor, un valor, una flecha).
 * Con [onClick] toda la fila se puede pulsar y, si no se pasa [trailing], se muestra una flecha.
 * Va dentro de una [UciCard] o [UciSectionCard], separada de las demás con [UciDivider].
 */
@Composable
fun UciSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    RowLayout(
        modifier = modifier.then(clickModifier),
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        trailing = trailing ?: onClick?.let {
            { Icon(UciIcons.ChevronRight, contentDescription = null, tint = ProxyUciTheme.colors.onSurfaceFaint) }
        },
    )
}

/** Fila con interruptor: se pulsa en cualquier punto y los lectores de pantalla la leen como un solo control. */
@Composable
fun UciSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    RowLayout(
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        trailing = { UciSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Fila con casilla. [mono] escribe el título en monoespaciada, para redes como «10.0.0.0/8». */
@Composable
fun UciCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
) {
    RowLayout(
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        title = title,
        subtitle = subtitle,
        icon = null,
        enabled = enabled,
        mono = mono,
        trailing = { UciCheckbox(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun RowLayout(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    enabled: Boolean,
    mono: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    val c = ProxyUciTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) c.onSurfaceMuted else c.onSurfaceFaint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (mono) ProxyUciTheme.mono.metricSmall else MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else c.onSurfaceFaint,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) c.onSurfaceMuted else c.onSurfaceFaint)
        }
        trailing?.invoke(this)
    }
}

/**
 * Fila de un perfil: indicador de estado, nombre, dirección en monoespaciada y etiquetas ([tags]).
 * Con [tone] distinto de neutro se resalta el borde (por ejemplo el perfil conectado).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UciProfileRow(
    name: String,
    address: String,
    modifier: Modifier = Modifier,
    status: UciTone = UciTone.Neutral,
    statusPulsing: Boolean = false,
    tone: UciTone = UciTone.Neutral,
    onClick: (() -> Unit)? = null,
    tags: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    UciCard(modifier = modifier.fillMaxWidth(), tone = tone, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
            UciStatusDot(status, pulsing = statusPulsing, size = 10)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(address, style = ProxyUciTheme.mono.code, color = ProxyUciTheme.colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
        }
        if (tags != null) {
            // Si no caben en una línea, pasan a la siguiente en vez de cortarse.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = tags)
        }
    }
}

/**
 * Lista que se ordena arrastrando el asa de cada fila. Pensada para listas cortas (servicios de nombres), no para
 * cientos de elementos. Los lectores de pantalla tienen las acciones «Subir» y «Bajar» en cada fila.
 *
 * [onMove] recibe la posición de origen y la de destino; quien llama reordena su lista.
 */
@Composable
fun <T> UciReorderableList(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    itemContent: @Composable RowScope.(item: T) -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val heights = remember(items.size) { mutableStateListOf<Int>().apply { repeat(items.size) { add(0) } } }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(0f) }
    val target = dragging?.let { reorderTarget(it, offset, heights, spacingPx) }

    val moveUp = stringResource(Res.string.ui_move_up)
    val moveDown = stringResource(Res.string.ui_move_down)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, item ->
            val isDragging = dragging == index
            val shift = when {
                dragging == null || target == null || isDragging -> 0f
                index in (dragging!! + 1)..target -> -(heights[dragging!!] + spacingPx)
                index in target until dragging!! -> heights[dragging!!] + spacingPx
                else -> 0f
            }
            val animatedShift by animateFloatAsState(shift)
            val actions = buildList {
                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true })
                if (index < items.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true })
            }
            UciCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { if (index < heights.size) heights[index] = it.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) offset else animatedShift
                        if (isDragging) {
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .semantics { customActions = actions },
                tone = if (isDragging) UciTone.Active else UciTone.Neutral,
                contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        UciIcons.DragHandle,
                        contentDescription = null,
                        tint = ProxyUciTheme.colors.onSurfaceFaint,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .pointerInput(index, items.size) {
                                detectDragGestures(
                                    onDragStart = { dragging = index; offset = 0f },
                                    onDragEnd = {
                                        val to = reorderTarget(index, offset, heights, spacingPx)
                                        dragging = null
                                        offset = 0f
                                        if (to != index) onMove(index, to)
                                    },
                                    onDragCancel = { dragging = null; offset = 0f },
                                    onDrag = { change, amount -> change.consume(); offset += amount.y },
                                )
                            },
                    )
                    itemContent(item)
                }
            }
        }
    }
}

/**
 * A qué posición va una fila arrastrada [offset] píxeles desde [from], sabiendo la altura de cada fila.
 * Cambia de sitio con la vecina cuando se ha pasado más de la mitad de ella.
 */
internal fun reorderTarget(from: Int, offset: Float, heights: List<Int>, spacing: Float): Int {
    var target = from
    var remaining = offset
    if (offset > 0) {
        while (target + 1 < heights.size) {
            val step = heights[target + 1] + spacing
            if (remaining > step / 2f) {
                remaining -= step
                target++
            } else {
                break
            }
        }
    } else {
        while (target - 1 >= 0) {
            val step = heights[target - 1] + spacing
            if (-remaining > step / 2f) {
                remaining += step
                target--
            } else {
                break
            }
        }
    }
    return target
}

/** Pantalla o sección vacía: icono, título, explicación y una acción opcional («Añadir perfil»). */
@Composable
fun UciEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    message: String? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(ProxyUciTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall),
    ) {
        if (icon != null) UciIconBox(icon, size = 56)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted, textAlign = TextAlign.Center)
        }
        action?.invoke(this)
    }
}
