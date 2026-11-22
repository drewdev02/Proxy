package org.uci.proxy.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.model.ProtocolIssue
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.RefusalReason
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.model.OvpnConfigError
import org.uci.proxy.core.model.VpnHandshakeIssue
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*

/*
 * Los textos de los fallos, en un solo sitio para que todas las pantallas digan lo mismo. Están en
 * composeResources/values/strings_errors.xml (y su traducción en cada values-xx); aquí solo se elige cuál corresponde a cada fallo.
 *
 * Los `when` no tienen `else`: un fallo nuevo en el núcleo no compila hasta que tenga su texto.
 */

/** Por qué falló la conexión del túnel, como frase completa que dice qué pasó y, si se sabe, qué hacer. */
@Composable
internal fun TunnelFailure.reasonText(): String = when (this) {
    TunnelFailure.VpnPermissionDenied -> stringResource(Res.string.error_tunnel_vpn_permission_denied)
    TunnelFailure.LocalNetworkDenied -> stringResource(Res.string.error_tunnel_local_network_denied)
    TunnelFailure.InterfaceUnavailable -> stringResource(Res.string.error_tunnel_interface_unavailable)
    TunnelFailure.EngineStopped -> stringResource(Res.string.error_tunnel_engine_stopped)
    TunnelFailure.ProfileNotFound -> stringResource(Res.string.error_tunnel_profile_not_found)
    TunnelFailure.NoNetwork -> stringResource(Res.string.error_tunnel_no_network)
    is TunnelFailure.ProxyHostUnresolved -> stringResource(Res.string.error_tunnel_proxy_host_unresolved, host)
    is TunnelFailure.ProxyUnreachable -> stringResource(kind.proxyReason)
    TunnelFailure.ProxyAuthRequired -> stringResource(Res.string.error_tunnel_proxy_auth_required)
    TunnelFailure.ProxyAuthFailed -> stringResource(Res.string.error_tunnel_proxy_auth_failed)
    is TunnelFailure.ProxyAuthUnsupported ->
        if (schemes.isEmpty()) {
            stringResource(Res.string.error_proxy_unsupported_auth)
        } else {
            stringResource(Res.string.error_proxy_unsupported_auth_schemes, schemes.joinToString())
        }
    is TunnelFailure.ProxyProtocolMismatch -> stringResource(Res.string.error_proxy_protocol_mismatch, stringResource(issue.text))
    TunnelFailure.VpnSecretsMissing -> stringResource(Res.string.error_tunnel_vpn_secrets_missing)
    is TunnelFailure.OvpnInvalid -> stringResource(Res.string.error_tunnel_ovpn_invalid, error.message())
    TunnelFailure.TailscaleConfigInvalid -> stringResource(Res.string.error_tunnel_tailscale_config_invalid)
    TunnelFailure.EngineUnavailable -> stringResource(Res.string.error_tunnel_engine_unavailable)
    TunnelFailure.VpnServerUnreachable -> stringResource(Res.string.error_tunnel_vpn_server_unreachable)
    TunnelFailure.VpnServerBlocked -> stringResource(Res.string.error_tunnel_vpn_server_blocked)
    TunnelFailure.VpnAuthFailed -> stringResource(Res.string.error_tunnel_vpn_auth_failed)
    TunnelFailure.TailscaleKeyExpired -> stringResource(Res.string.error_tunnel_tailscale_key_expired)
    is TunnelFailure.VpnHandshakeFailed -> when (issue) {
        VpnHandshakeIssue.TIMEOUT -> stringResource(Res.string.error_tunnel_handshake_timeout)
        VpnHandshakeIssue.CLOSED -> stringResource(Res.string.error_tunnel_handshake_closed)
        VpnHandshakeIssue.SERVER_CERTIFICATE -> stringResource(Res.string.error_tunnel_handshake_server_certificate)
        VpnHandshakeIssue.TLS_FAILED -> stringResource(Res.string.error_tunnel_handshake_tls)
        VpnHandshakeIssue.UNSUPPORTED_CIPHER -> stringResource(Res.string.error_tunnel_handshake_cipher, subject ?: "?")
        VpnHandshakeIssue.UNSUPPORTED_SERVER -> stringResource(Res.string.error_tunnel_handshake_unsupported)
        VpnHandshakeIssue.SESSION_ENDED ->
            subject?.let { stringResource(Res.string.error_tunnel_handshake_session_ended_reason, it) }
                ?: stringResource(Res.string.error_tunnel_handshake_session_ended)
        VpnHandshakeIssue.UNAVAILABLE -> stringResource(Res.string.error_tunnel_engine_unavailable)
    }
    TunnelFailure.TailscaleBusy -> stringResource(Res.string.error_tunnel_tailscale_busy)
    is TunnelFailure.ExitNodeNotFound -> stringResource(Res.string.error_tunnel_exit_node_not_found, name)
    TunnelFailure.ExitNodeNotChosen -> stringResource(Res.string.error_tunnel_exit_node_not_chosen)
    TunnelFailure.EngineInternal -> stringResource(Res.string.error_tunnel_engine_internal)
}

/** La frase completa: «No se pudo conectar. …». */
@Composable
internal fun TunnelFailure.message(): String = stringResource(Res.string.error_tunnel_failed, reasonText())

/** Lo que la interfaz ofrece para arreglar un fallo, junto a su frase. */
internal enum class FailureAction {
    /** Cambiar datos del perfil: usuario, contraseña, servidor, protocolo, archivo .ovpn o nodo de salida. */
    EDIT_PROFILE,

    /** Probar el proxy, para ver qué le pasa. */
    TEST_PROXY,

    /** Dar a la app un permiso que se negó (red local). */
    OPEN_APP_SETTINGS,

    /** Quitar otra VPN que ocupa el sitio. */
    OPEN_VPN_SETTINGS,

    /** Volver a intentarlo: el fallo puede ser pasajero. */
    RETRY,
}

internal val TunnelFailure.action: FailureAction
    get() = when (this) {
        is TunnelFailure.ProxyHostUnresolved,
        TunnelFailure.ProxyAuthRequired,
        TunnelFailure.ProxyAuthFailed,
        is TunnelFailure.ProxyAuthUnsupported,
        is TunnelFailure.ProxyProtocolMismatch,
        TunnelFailure.VpnSecretsMissing,
        is TunnelFailure.OvpnInvalid,
        TunnelFailure.TailscaleConfigInvalid,
        TunnelFailure.EngineUnavailable,
        TunnelFailure.VpnServerBlocked,
        TunnelFailure.VpnAuthFailed,
        TunnelFailure.TailscaleKeyExpired,
        is TunnelFailure.ExitNodeNotFound,
        TunnelFailure.ExitNodeNotChosen,
        -> FailureAction.EDIT_PROFILE
        is TunnelFailure.ProxyUnreachable -> when (kind) {
            // El puerto o el nombre del perfil están mal; lo demás puede ser la red o el proxy, y la prueba lo dice.
            NetFailure.REFUSED, NetFailure.NAME_NOT_FOUND, NetFailure.PROTOCOL -> FailureAction.EDIT_PROFILE
            else -> FailureAction.TEST_PROXY
        }
        is TunnelFailure.VpnHandshakeFailed -> when (issue) {
            VpnHandshakeIssue.SERVER_CERTIFICATE, VpnHandshakeIssue.UNSUPPORTED_CIPHER, VpnHandshakeIssue.UNSUPPORTED_SERVER ->
                FailureAction.EDIT_PROFILE
            else -> FailureAction.RETRY
        }
        TunnelFailure.LocalNetworkDenied -> FailureAction.OPEN_APP_SETTINGS
        TunnelFailure.InterfaceUnavailable -> FailureAction.OPEN_VPN_SETTINGS
        TunnelFailure.VpnPermissionDenied,
        TunnelFailure.EngineStopped,
        TunnelFailure.ProfileNotFound,
        TunnelFailure.NoNetwork,
        TunnelFailure.VpnServerUnreachable,
        TunnelFailure.TailscaleBusy,
        TunnelFailure.EngineInternal,
        -> FailureAction.RETRY
    }

/** Por qué falló la búsqueda de nodos de salida de Tailscale, contado desde la pantalla de búsqueda. */
internal val TunnelFailure.exitSearchReason: StringResource
    get() = when (this) {
        TunnelFailure.VpnAuthFailed -> Res.string.error_exit_search_vpn_auth_failed
        TunnelFailure.TailscaleKeyExpired -> Res.string.error_exit_search_key_expired
        TunnelFailure.ProxyAuthFailed -> Res.string.error_exit_search_proxy_auth_failed
        TunnelFailure.VpnServerBlocked -> Res.string.error_exit_search_server_blocked
        TunnelFailure.VpnServerUnreachable -> Res.string.error_exit_search_server_unreachable
        TunnelFailure.EngineUnavailable -> Res.string.error_exit_search_engine_unavailable
        TunnelFailure.TailscaleConfigInvalid -> Res.string.error_exit_search_invalid_server
        TunnelFailure.TailscaleBusy -> Res.string.error_exit_search_busy
        else -> Res.string.error_exit_search_other
    }

/** Por qué no se llegó al proxy. */
internal val NetFailure.proxyReason: StringResource
    get() = when (this) {
        NetFailure.NO_NETWORK -> Res.string.error_net_no_network
        NetFailure.NAME_NOT_FOUND -> Res.string.error_net_name_not_found
        NetFailure.REFUSED -> Res.string.error_net_refused
        NetFailure.TIMEOUT -> Res.string.error_net_timeout
        NetFailure.NO_ROUTE -> Res.string.error_net_no_route
        NetFailure.RESET -> Res.string.error_net_reset
        NetFailure.CLOSED -> Res.string.error_net_closed
        NetFailure.PROTOCOL -> Res.string.error_net_protocol
        NetFailure.OTHER -> Res.string.error_net_other
    }

/** Por qué el proxy no abrió un destino. */
internal val RefusalReason.text: StringResource
    get() = when (this) {
        RefusalReason.FORBIDDEN -> Res.string.error_refusal_forbidden
        RefusalReason.DESTINATION_UNREACHABLE -> Res.string.error_refusal_destination_unreachable
        RefusalReason.DESTINATION_REFUSED -> Res.string.error_refusal_destination_refused
        RefusalReason.UNSUPPORTED_REQUEST -> Res.string.error_refusal_unsupported
        RefusalReason.OTHER -> Res.string.error_refusal_other
    }

/** Qué no cuadra en la respuesta del servidor. */
internal val ProtocolIssue.text: StringResource
    get() = when (this) {
        ProtocolIssue.NOT_HTTP_PROXY -> Res.string.error_protocol_not_http
        ProtocolIssue.NOT_SOCKS5 -> Res.string.error_protocol_not_socks5
        ProtocolIssue.BAD_SOCKS5_REPLY -> Res.string.error_protocol_bad_socks5_reply
        ProtocolIssue.BAD_NTLM_CHALLENGE -> Res.string.error_protocol_bad_ntlm
        ProtocolIssue.NO_CONNECTOR -> Res.string.error_protocol_no_connector
    }

/**
 * El resultado de probar el proxy, en una frase que dice qué pasó. Los detalles técnicos de [ProxyFailure] (la línea
 * de estado HTTP, el código SOCKS, el error del sistema) no van en la frase: se muestran aparte, en «Detalles».
 */
@Composable
internal fun ProxyTestResult.message(): String = when (this) {
    ProxyTestResult.Success -> stringResource(Res.string.error_proxy_test_success)
    ProxyTestResult.Timeout -> stringResource(Res.string.error_proxy_test_timeout)
    is ProxyTestResult.Failure -> when (val failure = failure) {
        ProxyFailure.AuthRequired -> stringResource(Res.string.error_proxy_auth_required)
        ProxyFailure.AuthRejected -> stringResource(Res.string.error_proxy_auth_rejected)
        is ProxyFailure.UnsupportedAuth ->
            if (failure.schemes.isEmpty()) {
                stringResource(Res.string.error_proxy_unsupported_auth)
            } else {
                stringResource(Res.string.error_proxy_unsupported_auth_schemes, failure.schemes.joinToString())
            }
        is ProxyFailure.Refused -> stringResource(Res.string.error_proxy_refused, stringResource(failure.reason.text))
        is ProxyFailure.Unreachable -> stringResource(failure.kind.proxyReason)
        is ProxyFailure.ProtocolError -> stringResource(Res.string.error_proxy_protocol_mismatch, stringResource(failure.issue.text))
    }
}

/** El texto de la app cuando le falta el permiso de red local para la prueba. */
internal val LocalNetworkDeniedForTest: StringResource get() = Res.string.error_local_network_denied_for_test

/** Por qué no sirve el archivo `.ovpn` elegido. */
@Composable
internal fun OvpnConfigError.message(): String = when (this) {
    is OvpnConfigError.UnclosedBlock -> stringResource(Res.string.error_ovpn_unclosed_block, block)
    OvpnConfigError.NotAClient -> stringResource(Res.string.error_ovpn_not_a_client)
    OvpnConfigError.TapNotSupported -> stringResource(Res.string.error_ovpn_tap_not_supported)
    OvpnConfigError.NoTcpServers -> stringResource(Res.string.error_ovpn_no_tcp_servers)
    OvpnConfigError.TlsAuthNotSupported -> stringResource(Res.string.error_ovpn_tls_auth)
    OvpnConfigError.TlsCryptV2NotSupported -> stringResource(Res.string.error_ovpn_tls_crypt_v2)
    OvpnConfigError.ClientCertificateNotSupported -> stringResource(Res.string.error_ovpn_client_certificate)
    is OvpnConfigError.CompressionNotSupported -> stringResource(Res.string.error_ovpn_compression, algorithm)
    OvpnConfigError.MissingCa -> stringResource(Res.string.error_ovpn_missing_ca)
    OvpnConfigError.MissingTlsCrypt -> stringResource(Res.string.error_ovpn_missing_tls_crypt)
    OvpnConfigError.BadTlsCryptKey -> stringResource(Res.string.error_ovpn_bad_tls_crypt_key)
    OvpnConfigError.InvalidCa -> stringResource(Res.string.error_ovpn_invalid_ca)
}

/** El código de un resultado fallido de la prueba del proxy, para «Detalles»; null si fue bien. */
internal val ProxyTestResult.code: String?
    get() = when (this) {
        ProxyTestResult.Success -> null
        ProxyTestResult.Timeout -> "PX-TIMEOUT"
        is ProxyTestResult.Failure -> when (val failure = failure) {
            ProxyFailure.AuthRequired -> "PX-AUTH-REQUIRED"
            ProxyFailure.AuthRejected -> "PX-AUTH"
            is ProxyFailure.UnsupportedAuth -> "PX-AUTH-SCHEME"
            is ProxyFailure.Refused -> "PX-REFUSED-${failure.reason.name}"
            is ProxyFailure.Unreachable -> "PX-NET-${failure.kind.name}"
            is ProxyFailure.ProtocolError -> "PX-PROTO-${failure.issue.name}"
        }
    }

/** Lo que dijo el proxy o el sistema, tal cual, para «Detalles»; null si no hay nada que añadir. */
internal val ProxyTestResult.technicalDetail: String?
    get() = when (val failure = (this as? ProxyTestResult.Failure)?.failure) {
        is ProxyFailure.Refused -> failure.detail
        is ProxyFailure.Unreachable -> failure.detail
        is ProxyFailure.ProtocolError -> failure.detail
        else -> null
    }?.takeIf { it.isNotBlank() }
