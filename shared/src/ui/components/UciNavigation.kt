package org.uci.proxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.icons.UciIcons

/**
 * Cabecera de pantalla. Con [onBack] muestra la flecha de volver. [actions] van a la derecha, normalmente
 * [UciIconButton] o un [UciStatusPill] de estado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UciTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ProxyUciTheme.colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) UciIconButton(UciIcons.Back, "Volver", onBack, bordered = false)
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = ProxyUciTheme.colors.card,
        ),
    )
}

/** Un destino de la navegación principal. [badge] es un número pequeño junto al icono (por ejemplo avisos nuevos). */
@Immutable
data class UciNavItem<K>(val key: K, val label: String, val icon: ImageVector, val badge: Int? = null)

/** Barra inferior para móvil. */
@Composable
fun <K> UciBottomNavBar(items: List<UciNavItem<K>>, selected: K, onSelect: (K) -> Unit, modifier: Modifier = Modifier) {
    val c = ProxyUciTheme.colors
    NavigationBar(modifier = modifier, containerColor = c.card, tonalElevation = 0.dp) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.key == selected,
                onClick = { onSelect(item.key) },
                icon = { NavIcon(item) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = c.control,
                    unselectedIconColor = c.onSurfaceFaint,
                    unselectedTextColor = c.onSurfaceFaint,
                ),
            )
        }
    }
}

/** Barra lateral para tableta y escritorio. [header] va arriba (el logo). */
@Composable
fun <K> UciNavigationRail(
    items: List<UciNavItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    windowInsets: WindowInsets = NavigationRailDefaults.windowInsets,
) {
    val c = ProxyUciTheme.colors
    NavigationRail(modifier = modifier, containerColor = c.card, header = header, windowInsets = windowInsets) {
        Spacer(Modifier.size(8.dp))
        items.forEach { item ->
            NavigationRailItem(
                selected = item.key == selected,
                onClick = { onSelect(item.key) },
                icon = { NavIcon(item) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = c.control,
                    unselectedIconColor = c.onSurfaceFaint,
                    unselectedTextColor = c.onSurfaceFaint,
                ),
            )
        }
    }
}

@Composable
private fun <K> NavIcon(item: UciNavItem<K>) {
    val count = item.badge
    if (count != null && count > 0) {
        BadgedBox(
            badge = {
                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(if (count > 99) "99+" else count.toString(), style = ProxyUciTheme.mono.badge)
                }
            },
        ) { Icon(item.icon, contentDescription = null) }
    } else {
        Icon(item.icon, contentDescription = null)
    }
}

/** Ancho a partir del cual se usa la barra lateral en lugar de la inferior. */
internal val WideLayoutBreakpoint = 600.dp

/**
 * Estructura de las pantallas principales: barra inferior en móvil y barra lateral cuando la ventana es ancha
 * (tableta, escritorio). [content] recibe si se está en modo ancho, para ajustar márgenes.
 *
 * Con [showNavigation] en false se ocultan las barras (pantallas de detalle como el editor) sin cambiar la estructura,
 * para que el contenido no pierda su estado al entrar y salir de ellas.
 *
 * [topInset] es el alto que ocupa encima la barra de título del sistema cuando la ventana dibuja por debajo de ella
 * (escritorio en macOS): el fondo de la barra lateral sube hasta arriba, pero sus botones y el contenido empiezan debajo.
 */
@Composable
fun <K> UciNavigationScaffold(
    items: List<UciNavItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    topInset: Dp = 0.dp,
    content: @Composable (wide: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WideLayoutBreakpoint
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                if (showNavigation) {
                    UciNavigationRail(items, selected, onSelect, Modifier.fillMaxHeight(), windowInsets = WindowInsets(top = topInset))
                    VerticalDivider(color = ProxyUciTheme.colors.cardBorder)
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(top = topInset)) { content(true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = topInset)) { content(false) }
                if (showNavigation) UciBottomNavBar(items, selected, onSelect)
            }
        }
    }
}

/**
 * Diálogo de confirmación. Con [destructive] el botón de confirmar es rojo (borrar un perfil).
 */
@Composable
fun UciConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancelar",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProxyUciTheme.colors.card,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted) },
        confirmButton = {
            UciButton(confirmLabel, onConfirm, variant = if (destructive) UciButtonVariant.Danger else UciButtonVariant.Primary)
        },
        dismissButton = { UciButton(dismissLabel, onDismiss, variant = UciButtonVariant.Text) },
    )
}

/** Hoja inferior modal con título, para elegir entre opciones o completar un paso corto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UciBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Sin estado intermedio: la hoja se abre entera o se cierra.
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = ProxyUciTheme.colors.card,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ProxyUciTheme.spacing.screenMargin).padding(bottom = ProxyUciTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
