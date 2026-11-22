package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.LocalNetworkDeniedForTest
import org.uci.proxy.common.message
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.icons.UciIcons
import org.uci.proxy.common.FailureDetails
import org.uci.proxy.common.code
import org.uci.proxy.common.technicalDetail

@Composable
internal fun ServerSection(state: ProfileEditorUiState, onDraftChange: (ProfileDraft) -> Unit) {
    val draft = state.draft
    UciSectionCard(
        title = stringResource(Res.string.profile_server_title),
        subtitle = stringResource(Res.string.profile_server_subtitle),
        icon = UciIcons.Dns,
    ) {
        UciTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = stringResource(Res.string.profile_name_label),
            placeholder = stringResource(Res.string.profile_name_placeholder),
            error = state.errorFor(ProfileField.NAME),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
            UciTextField(
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                label = stringResource(Res.string.profile_host_label),
                placeholder = stringResource(Res.string.profile_host_placeholder),
                mono = true,
                error = state.errorFor(ProfileField.HOST),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            UciTextField(
                value = draft.port,
                onValueChange = { onDraftChange(draft.copy(port = it)) },
                label = stringResource(Res.string.profile_port_label),
                placeholder = "3128",
                mono = true,
                error = state.errorFor(ProfileField.PORT),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(Res.string.profile_protocol_label), style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        UciSegmentedControl(
            options = ProxyProtocol.entries,
            selected = draft.protocol,
            onSelect = { onDraftChange(draft.copy(protocol = it)) },
            label = { it.label },
        )
    }
}

@Composable
internal fun AuthSection(
    state: ProfileEditorUiState,
    onDraftChange: (ProfileDraft) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onUciQuotaChange: (Boolean) -> Unit,
) {
    val draft = state.draft
    UciSectionCard(
        title = stringResource(Res.string.profile_auth_title),
        subtitle = stringResource(Res.string.profile_auth_subtitle),
        icon = UciIcons.Key,
    ) {
        UciTextField(
            value = draft.username,
            onValueChange = { onDraftChange(draft.copy(username = it)) },
            label = stringResource(Res.string.profile_username_label),
            placeholder = stringResource(Res.string.profile_username_placeholder),
            leadingIcon = UciIcons.Person,
        )
        UciPasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(Res.string.profile_password_label),
            supportingText = stringResource(Res.string.profile_password_supporting),
        )
        UciButton(
            stringResource(if (state.test == ProxyTestUiState.Running) Res.string.profile_test_running else Res.string.profile_test_button),
            onTest,
            variant = UciButtonVariant.Secondary,
            icon = UciIcons.Speed,
            loading = state.test == ProxyTestUiState.Running,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val test = state.test) {
            ProxyTestUiState.Idle, ProxyTestUiState.Running -> Unit
            ProxyTestUiState.LocalNetworkDenied -> UciInlineAlert(stringResource(LocalNetworkDeniedForTest), tone = UciTone.Warning)
            is ProxyTestUiState.Finished -> Column {
                UciInlineAlert(
                    test.result.message(),
                    tone = when (test.result) {
                        ProxyTestResult.Success -> UciTone.Success
                        ProxyTestResult.Timeout -> UciTone.Warning
                        is ProxyTestResult.Failure -> UciTone.Danger
                    },
                )
                test.result.code?.let { FailureDetails(it, test.result.technicalDetail) }
            }
        }
        UciDivider()
        UciSwitchRow(
            title = stringResource(Res.string.profile_uci_quota_title),
            subtitle = stringResource(Res.string.profile_uci_quota_subtitle),
            checked = state.checksUciQuota,
            onCheckedChange = onUciQuotaChange,
            enabled = !state.isBusy,
        )
    }
}
