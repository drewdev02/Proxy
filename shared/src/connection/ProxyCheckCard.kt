package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.uci.proxy.common.LOCAL_NETWORK_DENIED_FOR_TEST
import org.uci.proxy.common.message
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

@Composable
internal fun ProxyCheckCard(state: ConnectionUiState, onTest: () -> Unit) {
    val check = state.check
    UciSectionCard(
        title = "Probar el proxy",
        subtitle = "Comprueba el usuario y la contraseña con el proxy",
        icon = UciIcons.Speed,
        trailing = {
            UciButton(
                "Probar",
                onTest,
                variant = UciButtonVariant.Secondary,
                loading = check == ProxyCheck.Running,
            )
        },
    ) {
        when (check) {
            ProxyCheck.Idle, ProxyCheck.Running -> Unit
            ProxyCheck.LocalNetworkDenied -> UciInlineAlert(LOCAL_NETWORK_DENIED_FOR_TEST, tone = UciTone.Warning)
            is ProxyCheck.Finished -> when (check.result) {
                // El tiempo es el de la prueba entera (conectar, autenticarse, abrir el túnel), no una latencia: no se
                // le aplican los umbrales de latencia del diseño.
                ProxyTestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UciStatusPill("OK · ${check.millis} ms", tone = UciTone.Success, icon = UciIcons.Check)
                    Text("El proxy aceptó los datos del perfil.", style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
                }
                ProxyTestResult.Timeout -> UciInlineAlert(check.result.message(), tone = UciTone.Warning)
                is ProxyTestResult.Failure -> UciInlineAlert(check.result.message(), tone = UciTone.Danger)
            }
        }
    }
}
