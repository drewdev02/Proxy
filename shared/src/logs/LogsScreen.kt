package org.uci.proxy.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciConfirmDialog
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciStatusDot
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons

/** Los niveles que se pueden elegir como filtro, con el nombre que se muestra. */
private val levelFilters = listOf(
    LogLevel.DEBUG to "Todo",
    LogLevel.INFO to "Info",
    LogLevel.WARN to "Avisos",
    LogLevel.ERROR to "Errores",
)

/** El registro a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado. */
@Composable
internal fun LogsScreen(onBack: (() -> Unit)? = null, viewModel: LogsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // LocalClipboard (con ClipEntry) no tiene un constructor común a todas las plataformas; el gestor clásico sí.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    LogsScreen(
        state = state,
        onBack = onBack,
        onLevelChange = viewModel::setMinLevel,
        onTogglePause = viewModel::togglePause,
        onClear = viewModel::clear,
        onCopy = { clipboard.setText(AnnotatedString(viewModel.textToCopy())) },
    )
}

/**
 * El registro sin estado (salvo la confirmación de «Limpiar»): muestra [state] y avisa de lo que se pulsa. Sigue al
 * último mensaje salvo en pausa. Filas compactas y no tarjetas: el registro puede tener miles de líneas.
 */
@Composable
fun LogsScreen(
    state: LogsUiState,
    /** Null cuando Registro es una pestaña de la navegación principal: no hay a dónde volver. */
    onBack: (() -> Unit)?,
    onLevelChange: (LogLevel) -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val listState = rememberLazyListState()
    var confirmingClear by remember { mutableStateOf(false) }
    val spacing = ProxyUciTheme.spacing

    // Mientras no esté en pausa, cada mensaje nuevo desplaza la lista hasta el final.
    LaunchedEffect(state.entries.size, state.paused) {
        if (!state.paused && state.entries.isNotEmpty()) listState.scrollToItem(state.entries.lastIndex)
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        UciTopBar(
            title = "Registro",
            subtitle = summary(state),
            onBack = onBack,
            actions = {
                UciIconButton(
                    if (state.paused) UciIcons.Play else UciIcons.Pause,
                    if (state.paused) "Reanudar" else "Pausar",
                    onTogglePause,
                    tone = if (state.paused) UciTone.Warning else UciTone.Neutral,
                )
                UciIconButton(UciIcons.Copy, "Copiar lo que se ve", onCopy, enabled = state.entries.isNotEmpty())
                UciIconButton(UciIcons.Delete, "Limpiar el registro", { confirmingClear = true }, tone = UciTone.Danger, enabled = state.total > 0)
            },
        )
        // Filtros y lista en la misma columna centrada, para que en escritorio queden alineados.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                // Una sola línea que se desliza si no cabe: así los filtros no se parten en dos filas en móvil.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screenMargin, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    levelFilters.forEach { (level, name) ->
                        UciFilterChip(name, state.minLevel == level, { onLevelChange(level) }, dot = level.tone.takeIf { level != LogLevel.DEBUG })
                    }
                }

                if (state.entries.isEmpty()) {
                    UciEmptyState(
                        title = if (state.total == 0) "Todavía no hay mensajes" else "Ningún mensaje con este filtro",
                        icon = UciIcons.Logs,
                        message = if (state.total == 0) "Aquí aparece lo que hace el túnel mientras está conectado." else "Elige «Todo» para ver los ${state.total} mensajes.",
                        modifier = Modifier.padding(top = spacing.xl),
                    )
                } else {
                    UciCard(
                        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screenMargin).padding(top = spacing.sm, bottom = spacing.md),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = spacing.xs),
                        ) {
                            itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                                if (index > 0) UciDivider(Modifier.padding(horizontal = spacing.md))
                                LogRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingClear) {
        UciConfirmDialog(
            title = "¿Limpiar el registro?",
            message = "Se borran los ${state.total} mensajes, también los guardados en el equipo. No se puede deshacer.",
            confirmLabel = "Limpiar",
            destructive = true,
            onConfirm = {
                confirmingClear = false
                onClear()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

private fun summary(state: LogsUiState): String {
    val shown = if (state.entries.size == state.total) "${state.total} mensajes" else "${state.entries.size} de ${state.total} mensajes"
    return if (state.paused) "En pausa · $shown" else "En vivo · $shown"
}

/** Una línea del registro: nivel, hora, origen y mensaje. */
@Composable
private fun LogRow(entry: LogEntry) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ProxyUciTheme.spacing.md, vertical = 8.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UciStatusDot(entry.level.tone, modifier = Modifier.padding(top = 5.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatLogTime(entry.epochMillis), style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
                Text(entry.tag, style = ProxyUciTheme.mono.badge, color = entry.level.tone.colors().content)
            }
            Text(
                entry.message,
                style = ProxyUciTheme.mono.code,
                color = if (entry.level == LogLevel.DEBUG) ProxyUciTheme.colors.onSurfaceMuted else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** El color de cada nivel: el mismo en el registro y en la actividad reciente de Conexión. */
internal val LogLevel.tone: UciTone
    get() = when (this) {
        LogLevel.ERROR -> UciTone.Danger
        LogLevel.WARN -> UciTone.Warning
        LogLevel.INFO -> UciTone.Active
        LogLevel.DEBUG -> UciTone.Neutral
    }

@Preview
@Composable
private fun LogsScreenPreview() {
    ProxyUciTheme {
        LogsScreen(
            state = LogsUiState(
                entries = listOf(
                    LogEntry(0, 1_700_000_000_000, LogLevel.INFO, "Tunnel", "Túnel activo"),
                    LogEntry(1, 1_700_000_001_250, LogLevel.WARN, "Relay", "El proxy no abrió example.org:443"),
                    LogEntry(2, 1_700_000_002_500, LogLevel.ERROR, "Relay", "Conexión rechazada"),
                ),
                total = 3,
            ),
            onBack = {},
            onLevelChange = {},
            onTogglePause = {},
            onClear = {},
            onCopy = {},
        )
    }
}
