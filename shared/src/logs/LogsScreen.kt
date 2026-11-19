package org.uci.proxy.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.theme.ProxyUciTheme

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

/** Sin estado propio: muestra [state] y avisa de lo que se pulsa. Sigue al último mensaje salvo en pausa. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    // Mientras no esté en pausa, cada mensaje nuevo desplaza la lista hasta el final.
    LaunchedEffect(state.entries.size, state.paused) {
        if (!state.paused && state.entries.isNotEmpty()) listState.scrollToItem(state.entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro") },
                navigationIcon = { onBack?.let { TextButton(onClick = it) { Text("Volver") } } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                levelFilters.forEach { (level, name) ->
                    FilterChip(
                        selected = state.minLevel == level,
                        onClick = { onLevelChange(level) },
                        label = { Text(name) },
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onTogglePause) { Text(if (state.paused) "Reanudar" else "Pausar") }
                TextButton(onClick = onClear) { Text("Limpiar") }
                TextButton(onClick = onCopy, enabled = state.entries.isNotEmpty()) { Text("Copiar") }
            }
            Text(
                summary(state),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.total == 0) "Todavía no hay mensajes." else "Ningún mensaje con este filtro.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry -> LogLine(entry) }
                }
            }
        }
    }
}

private fun summary(state: LogsUiState): String {
    val shown = if (state.entries.size == state.total) "${state.total} mensajes" else "${state.entries.size} de ${state.total} mensajes"
    return if (state.paused) "En pausa · $shown" else shown
}

@Composable
private fun LogLine(entry: LogEntry) {
    Text(
        entry.toLine(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = entry.level.color(),
    )
}

@Composable
private fun LogLevel.color(): Color = when (this) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
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
