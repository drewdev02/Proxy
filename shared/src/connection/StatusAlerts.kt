package org.uci.proxy.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.FailureAction
import org.uci.proxy.common.FailureDetails
import org.uci.proxy.common.SystemSettingsPage
import org.uci.proxy.common.action
import org.uci.proxy.common.code
import org.uci.proxy.common.reasonText
import org.uci.proxy.core.port.ProxyProblem
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.model.QuotaSettings
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.core.port.code

/** «El DNS de Otro País no resolvió a.com, b.com y 3 más: …», con los primeros nombres y cuántos quedan. */
@Composable
private fun viaProxyMessage(hosts: List<String>): String {
    val (names, rest) = viaProxyHosts(hosts)
    val list = names.joinToString(", ")
    return if (rest > 0) {
        pluralStringResource(Res.plurals.connection_via_proxy_message_more, rest, list, rest)
    } else {
        stringResource(Res.string.connection_via_proxy_message, list)
    }
}

@Composable
internal fun StatusAlerts(state: ConnectionUiState, actions: ConnectionActions, onEdit: () -> Unit) {
    if (state.quota.paused && state.tunnel !is TunnelState.Connected) {
        UciInlineAlert(
            stringResource(Res.string.connection_quota_paused_message, QuotaSettings.PAUSE_THRESHOLD, QuotaSettings.PAUSE_THRESHOLD - 5),
            title = stringResource(Res.string.connection_quota_paused_title),
            tone = UciTone.Danger,
            action = { UciButton(stringResource(Res.string.connection_connect_anyway), actions.onConnect, variant = UciButtonVariant.Text) },
        )
    }
    when (val tunnel = state.tunnel) {
        is TunnelState.Failed -> Column {
            UciInlineAlert(
                tunnel.failure.reasonText(),
                title = stringResource(Res.string.connection_failed_title),
                tone = UciTone.Danger,
                action = { FailureActionButton(tunnel.failure.action, actions, onEdit) },
            )
            FailureDetails(tunnel.failure.code)
        }
        is TunnelState.Connected -> when (tunnel.proxyProblem) {
            // Lo más grave primero: con el proxy fallando, no carga nada de lo que pasa por él.
            ProxyProblem.AUTH_REJECTED -> UciInlineAlert(
                stringResource(Res.string.connection_proxy_auth_rejected_message),
                title = stringResource(Res.string.connection_proxy_failing_title),
                tone = UciTone.Danger,
                action = { UciButton(stringResource(Res.string.connection_edit_profile), onEdit, variant = UciButtonVariant.Text) },
            )
            ProxyProblem.UNREACHABLE -> UciInlineAlert(
                stringResource(Res.string.connection_proxy_unreachable_message),
                title = stringResource(Res.string.connection_proxy_failing_title),
                tone = UciTone.Danger,
                action = { UciButton(stringResource(Res.string.connection_test_proxy_action), actions.onTestProxy, variant = UciButtonVariant.Text) },
            )
            null -> ConnectedAlerts(tunnel)
        }
        else -> Unit
    }
}

/** El botón que arregla un fallo: editar el perfil, probar el proxy, ir a los ajustes del sistema o reintentar. */
@Composable
private fun FailureActionButton(action: FailureAction, actions: ConnectionActions, onEdit: () -> Unit) {
    val settingsPage = when (action) {
        FailureAction.OPEN_APP_SETTINGS -> SystemSettingsPage.APP
        FailureAction.OPEN_VPN_SETTINGS -> SystemSettingsPage.VPN
        else -> null
    }
    when {
        action == FailureAction.EDIT_PROFILE ->
            UciButton(stringResource(Res.string.connection_edit_profile), onEdit, variant = UciButtonVariant.Text)
        action == FailureAction.TEST_PROXY ->
            UciButton(stringResource(Res.string.connection_test_proxy_action), actions.onTestProxy, variant = UciButtonVariant.Text)
        settingsPage != null && actions.systemSettings.canOpen(settingsPage) -> UciButton(
            stringResource(if (settingsPage == SystemSettingsPage.APP) Res.string.failure_action_open_app_settings else Res.string.failure_action_open_vpn_settings),
            { actions.systemSettings.open(settingsPage) },
            variant = UciButtonVariant.Text,
        )
        // Reintentar, o donde no se puede llevar a los ajustes del sistema.
        else -> UciButton(stringResource(Res.string.connection_retry), actions.onConnect, variant = UciButtonVariant.Text)
    }
}

/** Los avisos de un túnel conectado con el proxy funcionando. */
@Composable
private fun ConnectedAlerts(tunnel: TunnelState.Connected) {
    when {
        tunnel.vpnDown -> UciInlineAlert(
            stringResource(if (tunnel.vpnBlocked) Res.string.connection_vpn_down_blocked_message else Res.string.connection_vpn_down_message),
            title = stringResource(Res.string.connection_vpn_down_title),
            tone = UciTone.Warning,
        )
        // Otro País está, pero su DNS no resolvió algunos nombres: esos salen por el proxy y hay que decirlo.
        tunnel.viaProxy.isNotEmpty() -> UciInlineAlert(
            viaProxyMessage(tunnel.viaProxy),
            title = stringResource(Res.string.connection_via_proxy_title),
            tone = UciTone.Warning,
        )
    }
}
