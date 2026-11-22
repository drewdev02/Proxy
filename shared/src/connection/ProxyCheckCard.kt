package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.LocalNetworkDeniedForTest
import org.uci.proxy.common.FailureDetails
import org.uci.proxy.common.code
import org.uci.proxy.common.technicalDetail
import org.uci.proxy.common.message
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.core.port.code

@Composable
internal fun ProxyCheckCard(state: ConnectionUiState, onTest: () -> Unit) {
    val check = state.check
    UciSectionCard(
        title = stringResource(Res.string.connection_check_title),
        subtitle = stringResource(Res.string.connection_check_subtitle),
        icon = UciIcons.Speed,
        trailing = {
            UciButton(
                stringResource(Res.string.connection_check_button),
                onTest,
                variant = UciButtonVariant.Secondary,
                loading = check == ProxyCheck.Running,
            )
        },
    ) {
        when (check) {
            ProxyCheck.Idle, ProxyCheck.Running -> Unit
            ProxyCheck.LocalNetworkDenied -> UciInlineAlert(stringResource(LocalNetworkDeniedForTest), tone = UciTone.Warning)
            is ProxyCheck.Finished -> when (check.result) {
                // El tiempo es el de la prueba entera (conectar, autenticarse, abrir el túnel), no una latencia: no se
                // le aplican los umbrales de latencia del diseño.
                ProxyTestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UciStatusPill(stringResource(Res.string.connection_check_ok, check.millis), tone = UciTone.Success, icon = UciIcons.Check)
                    Text(stringResource(Res.string.connection_check_success), style = MaterialTheme.typography.bodyMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
                }
                ProxyTestResult.Timeout, is ProxyTestResult.Failure -> Column {
                    UciInlineAlert(
                        check.result.message(),
                        tone = if (check.result == ProxyTestResult.Timeout) UciTone.Warning else UciTone.Danger,
                    )
                    check.result.code?.let { FailureDetails(it, check.result.technicalDetail) }
                }
            }
        }
    }
}
