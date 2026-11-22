package org.uci.proxy.connection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.uci.proxy.common.isFixedInProfile
import org.uci.proxy.common.reason
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.core.model.QuotaSettings
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciTone

@Composable
internal fun StatusAlerts(state: ConnectionUiState, actions: ConnectionActions, onEdit: () -> Unit) {
    if (state.quota.paused && state.tunnel !is TunnelState.Connected) {
        UciInlineAlert(
            "Llegaste al ${QuotaSettings.PAUSE_THRESHOLD} % de tu cuota de la UCI y el túnel se desconectó para no agotarla. " +
                "Si conectas, no se vuelve a pausar hasta que tu consumo baje del ${QuotaSettings.PAUSE_THRESHOLD - 5} %.",
            title = "En pausa por la cuota",
            tone = UciTone.Danger,
            action = { UciButton("Conectar igual", actions.onConnect, variant = UciButtonVariant.Text) },
        )
    }
    when (val tunnel = state.tunnel) {
        is TunnelState.Failed -> UciInlineAlert(
            tunnel.failure.reason().replaceFirstChar { it.uppercase() },
            title = "No se pudo conectar",
            tone = UciTone.Danger,
            action = {
                if (tunnel.failure.isFixedInProfile) {
                    UciButton("Editar perfil", onEdit, variant = UciButtonVariant.Text)
                } else {
                    UciButton("Reintentar", actions.onConnect, variant = UciButtonVariant.Text)
                }
            },
        )
        is TunnelState.Connected -> when {
            tunnel.vpnDown -> UciInlineAlert(
                "Lo que debía salir por Otro País sale por el proxy mientras se reintenta la VPN.",
                title = "Otro País no responde",
                tone = UciTone.Warning,
            )
            // Otro País está, pero su DNS no resolvió algunos nombres: esos salen por el proxy y hay que decirlo.
            tunnel.viaProxy.isNotEmpty() -> UciInlineAlert(
                viaProxyMessage(tunnel.viaProxy),
                title = "Algunos sitios salen por el proxy",
                tone = UciTone.Warning,
            )
        }
        else -> Unit
    }
}
