package org.uci.proxy.profile

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.resources.*
import org.uci.proxy.resources.Res

/**
 * Qué decir de un campo que no vale. La validación (en el núcleo) solo dice qué campo es; el texto se elige aquí. Sin
 * `else`: un campo nuevo no compila hasta que tenga su texto.
 */
private val ProfileField.error: StringResource
    get() = when (this) {
        ProfileField.NAME -> Res.string.profile_name_error
        ProfileField.HOST -> Res.string.profile_host_error
        ProfileField.PORT -> Res.string.profile_port_error
        ProfileField.VPN_FILE -> Res.string.error_ovpn_missing
        ProfileField.VPN_USERNAME -> Res.string.profile_vpn_username_error
        ProfileField.TAILSCALE_CONTROL_URL -> Res.string.profile_tailscale_control_url_error
        ProfileField.TAILSCALE_AUTH_KEY -> Res.string.profile_tailscale_auth_key_error
    }

/** El texto del error de [field], o null si ese campo está bien. */
@Composable
internal fun ProfileEditorUiState.errorFor(field: ProfileField): String? =
    if (field in errors) stringResource(field.error) else null
