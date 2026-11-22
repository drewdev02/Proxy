package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.message
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciFileField
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

/** «Otro País»: encadena tras el proxy un motor (OpenVPN o Tailscale). Opcional: apagado, el perfil sale directo. */
@Composable
internal fun CountryExitSection(state: ProfileEditorUiState, actions: CountryExitActions, onPickFile: () -> Unit) {
    val vpn = state.vpn
    UciSectionCard(
        title = stringResource(Res.string.profile_country_exit_title),
        subtitle = stringResource(Res.string.profile_country_exit_subtitle),
        icon = UciIcons.Globe,
        tone = if (vpn.enabled) UciTone.Routing else UciTone.Neutral,
        trailing = {
            UciSwitch(
                vpn.enabled,
                actions::onVpnEnabledChange,
                enabled = !state.isBusy,
                contentDescription = stringResource(Res.string.profile_country_exit_toggle),
            )
        },
    ) {
        if (!vpn.enabled) return@UciSectionCard
        if (state.engines.size > 1) {
            val descriptions = CountryEngineKind.entries.associateWith { stringResource(it.description) }
            UciSegmentedControl(
                options = state.engines,
                selected = vpn.engine,
                onSelect = actions::onEngineChange,
                label = { it.label },
                description = { descriptions[it] },
                enabled = !state.isBusy,
            )
        }
        when (vpn.engine) {
            CountryEngineKind.OPENVPN -> OpenVpnFields(state, actions, onPickFile)
            CountryEngineKind.TAILSCALE -> TailscaleFields(state, actions)
        }
    }
}

@Composable
private fun OpenVpnFields(state: ProfileEditorUiState, actions: CountryExitActions, onPickFile: () -> Unit) {
    val vpn = state.vpn
    UciFileField(
        fileName = vpn.fileName.ifEmpty { null },
        onPick = onPickFile,
        detail = stringResource(Res.string.profile_ovpn_detail),
        emptyText = stringResource(Res.string.profile_ovpn_empty),
        chooseLabel = stringResource(Res.string.profile_ovpn_choose),
        changeLabel = stringResource(Res.string.profile_ovpn_change),
        enabled = !state.isBusy,
    )
    val fileError = vpn.fileError?.message() ?: state.errorFor(ProfileField.VPN_FILE)
    fileError?.let { UciInlineAlert(it, tone = UciTone.Danger) }
    if (vpn.needsCredentials) {
        UciTextField(
            value = vpn.username,
            onValueChange = actions::onVpnUsernameChange,
            label = stringResource(Res.string.profile_vpn_username_label),
            leadingIcon = UciIcons.Person,
            error = state.errorFor(ProfileField.VPN_USERNAME),
        )
        UciPasswordField(
            value = vpn.password,
            onValueChange = actions::onVpnPasswordChange,
            label = stringResource(Res.string.profile_vpn_password_label),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailscaleFields(state: ProfileEditorUiState, actions: CountryExitActions) {
    val tailscale = state.vpn.tailscale
    Text(
        stringResource(Res.string.profile_tailscale_description),
        style = MaterialTheme.typography.bodySmall,
        color = ProxyUciTheme.colors.onSurfaceMuted,
    )
    UciTextField(
        value = tailscale.controlUrl,
        onValueChange = actions::onTailscaleControlUrlChange,
        label = stringResource(Res.string.profile_tailscale_control_url_label),
        mono = true,
        error = state.errorFor(ProfileField.TAILSCALE_CONTROL_URL),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    UciPasswordField(
        value = tailscale.authKey,
        onValueChange = actions::onTailscaleAuthKeyChange,
        label = stringResource(Res.string.profile_tailscale_auth_key_label),
        placeholder = "tskey-auth-…",
        supportingText = stringResource(Res.string.profile_tailscale_auth_key_supporting),
        error = state.errorFor(ProfileField.TAILSCALE_AUTH_KEY),
    )
    UciTextField(
        value = tailscale.exitNode,
        onValueChange = actions::onTailscaleExitNodeChange,
        label = stringResource(Res.string.profile_tailscale_exit_node_label),
        placeholder = stringResource(Res.string.profile_tailscale_exit_node_placeholder),
        mono = true,
    )
    val searching = tailscale.search == ExitNodeSearch.Running
    UciButton(
        stringResource(if (searching) Res.string.profile_tailscale_searching else Res.string.profile_tailscale_search),
        actions::searchExitNodes,
        variant = UciButtonVariant.Secondary,
        icon = UciIcons.Search,
        loading = searching,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    when (val search = tailscale.search) {
        is ExitNodeSearch.Found -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            search.nodes.forEach { node ->
                UciFilterChip(
                    label = listOfNotNull(node.name, node.place.ifEmpty { null }).joinToString(" · "),
                    selected = tailscale.exitNode.equals(node.selector, ignoreCase = true),
                    onClick = { actions.onTailscaleExitNodeChange(node.selector) },
                    dot = if (node.online) UciTone.Success else UciTone.Neutral,
                )
            }
        }
        is ExitNodeSearch.Failed -> UciInlineAlert(stringResource(search.reason), tone = UciTone.Danger)
        else -> Unit
    }
    UciTextField(
        value = tailscale.hostname,
        onValueChange = actions::onTailscaleHostnameChange,
        label = stringResource(Res.string.profile_tailscale_hostname_label),
        mono = true,
    )
}

/** El nombre del motor: es una marca, no se traduce. */
internal val CountryEngineKind.label: String
    get() = when (this) {
        CountryEngineKind.OPENVPN -> "OpenVPN"
        CountryEngineKind.TAILSCALE -> "Tailscale"
    }

/** Lo que el motor pide, bajo su nombre en el selector. */
private val CountryEngineKind.description: StringResource
    get() = when (this) {
        CountryEngineKind.OPENVPN -> Res.string.profile_engine_openvpn_description
        CountryEngineKind.TAILSCALE -> Res.string.profile_engine_tailscale_description
    }
