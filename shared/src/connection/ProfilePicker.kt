package org.uci.proxy.connection

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.logs.tone
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.tone
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciSettingRow
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons

@Composable
internal fun ProfilePicker(state: ConnectionUiState, onSelect: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    UciBottomSheet(stringResource(Res.string.connection_choose_profile), onDismiss = onDismiss) {
        UciCard(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            state.profiles.forEachIndexed { index, profile ->
                if (index > 0) UciDivider()
                val active = profile.id == state.activeProfile?.id
                UciSettingRow(
                    title = profile.name,
                    subtitle = "${profile.protocol.name} · ${profile.host}:${profile.port}",
                    icon = if (active) UciIcons.CheckCircle else UciIcons.Badge,
                    onClick = { onSelect(profile.id) },
                    trailing = if (active) {
                        { UciStatusPill(stringResource(Res.string.connection_profile_active), tone = UciTone.Active) }
                    } else {
                        null
                    },
                )
            }
        }
        UciButton(stringResource(Res.string.connection_add_profile), onAdd, variant = UciButtonVariant.Secondary, icon = UciIcons.Add, modifier = Modifier.fillMaxWidth())
    }
}
