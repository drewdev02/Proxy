package org.uci.proxy.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.profile.label
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPowerButton
import org.uci.proxy.ui.components.UciPowerState

@Composable
internal fun PowerArea(state: ConnectionUiState, actions: ConnectionActions) {
    if (!state.tunnelAvailable) {
        UciInlineAlert(
            "En este sistema todavía no se puede crear el túnel. Puedes gestionar los perfiles y probar el proxy.",
            title = "Túnel no disponible",
        )
        return
    }
    val (powerState, label) = when (state.tunnel) {
        TunnelState.Disconnected -> UciPowerState.Idle to "Conectar"
        is TunnelState.Connecting -> UciPowerState.Connecting to "Conectando"
        is TunnelState.Connected -> UciPowerState.Connected to "Conectado"
        is TunnelState.Failed -> UciPowerState.Error to "Reintentar"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        UciPowerButton(
            state = powerState,
            label = label,
            onClick = {
                when (state.tunnel) {
                    is TunnelState.Connected, is TunnelState.Connecting -> actions.onDisconnect()
                    else -> actions.onConnect()
                }
            },
        )
        StatusLine(state)
    }
}

@Composable
private fun StatusLine(state: ConnectionUiState) {
    val text = when (state.tunnel) {
        TunnelState.Disconnected -> "Sin conexión · pulsa para conectar"
        is TunnelState.Connecting -> "Conectando con ${state.activeProfile?.host.orEmpty()}…"
        is TunnelState.Connected -> {
            // Si la VPN está caída lo explica el aviso de debajo; aquí no se repite.
            val since = state.connectedSince
            if (since == null) {
                "Conectado · pulsa para desconectar"
            } else {
                var elapsed by remember(since) { mutableStateOf(since.elapsedNow()) }
                LaunchedEffect(since) {
                    while (true) {
                        elapsed = since.elapsedNow()
                        delay(1.seconds)
                    }
                }
                "Conectado · ${formatElapsed(elapsed)}"
            }
        }
        is TunnelState.Failed -> "No se pudo conectar"
    }
    Text(
        text,
        style = ProxyUciTheme.mono.metricSmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
