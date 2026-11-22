package org.uci.proxy.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.code
import org.uci.proxy.common.percentText
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.core.quota.wholePercent
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciIconBox
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.core.port.code

@Composable
internal fun ActiveProfileCard(state: ConnectionUiState, profile: ProxyProfile, onChange: () -> Unit, onEdit: () -> Unit) {
    val connected = state.tunnel is TunnelState.Connected
    // Toda la tarjeta abre el editor del perfil: así la dirección tiene sitio y no se corta en móvil.
    UciCard(Modifier.fillMaxWidth(), tone = if (connected) UciTone.Success else UciTone.Neutral, onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UciIconBox(UciIcons.Badge, tone = if (connected) UciTone.Success else UciTone.Neutral)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.connection_profile_label), style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    profile.address(),
                    style = ProxyUciTheme.mono.code,
                    color = ProxyUciTheme.colors.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.profiles.size > 1) {
                UciIconButton(
                    UciIcons.Swap,
                    stringResource(if (state.canChangeProfile) Res.string.connection_change_profile else Res.string.connection_change_profile_disconnect),
                    onChange,
                    enabled = state.canChangeProfile,
                )
            }
        }
        // Las etiquetas saltan de línea si no caben (perfil con Otro País, reglas y cuota en un móvil estrecho).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UciStatusPill(profile.protocol.name)
            // El motor importa: OpenVPN y Tailscale fallan y se configuran distinto.
            profile.countryExit?.let { UciStatusPill(stringResource(Res.string.connection_pill_country, it.kind.label), tone = UciTone.Routing) }
            if (profile.routing.enabled) UciStatusPill(stringResource(Res.string.connection_pill_rules), tone = UciTone.Routing)
            // La cuota de la UCI, si este perfil la consulta y ya se sabe.
            state.quota.quota?.takeIf { state.quota.enabled && state.quota.profileId == profile.id }?.let { quota ->
                UciStatusPill(stringResource(Res.string.connection_pill_quota, percentText(quota.wholePercent)), tone = quota.tone)
            }
        }
    }
}
