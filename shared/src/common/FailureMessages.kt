package org.uci.proxy.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.proxy.ProtocolIssue
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.RefusalReason
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.vpn.OvpnConfigError
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*

/*
 * Los textos de los fallos, en un solo sitio para que todas las pantallas digan lo mismo. Están en
 * composeResources/values/strings_errors.xml (y su traducción en cada values-xx); aquí solo se elige cuál corresponde a cada fallo.
 *
 * Los `when` no tienen `else`: un fallo nuevo en el núcleo no compila hasta que tenga su texto.
 */

/** Por qué falló la conexión del túnel, como frase completa. */
internal val TunnelFailure.reason: StringResource
    get() = when (this) {
        TunnelFailure.VPN_PERMISSION_DENIED -> Res.string.error_tunnel_vpn_permission_denied
        TunnelFailure.LOCAL_NETWORK_DENIED -> Res.string.error_tunnel_local_network_denied
        TunnelFailure.PROFILE_NOT_FOUND -> Res.string.error_tunnel_profile_not_found
        TunnelFailure.INTERFACE_UNAVAILABLE -> Res.string.error_tunnel_interface_unavailable
        TunnelFailure.PROXY_HOST_UNRESOLVED -> Res.string.error_tunnel_proxy_host_unresolved
        TunnelFailure.PROXY_AUTH_FAILED -> Res.string.error_tunnel_proxy_auth_failed
        TunnelFailure.VPN_PROFILE_INVALID -> Res.string.error_tunnel_vpn_profile_invalid
        TunnelFailure.VPN_SERVER_UNREACHABLE -> Res.string.error_tunnel_vpn_server_unreachable
        TunnelFailure.VPN_SERVER_BLOCKED -> Res.string.error_tunnel_vpn_server_blocked
        TunnelFailure.VPN_AUTH_FAILED -> Res.string.error_tunnel_vpn_auth_failed
        TunnelFailure.VPN_HANDSHAKE_FAILED -> Res.string.error_tunnel_vpn_handshake_failed
        TunnelFailure.EXIT_NODE_NOT_FOUND -> Res.string.error_tunnel_exit_node_not_found
        TunnelFailure.ENGINE_UNAVAILABLE -> Res.string.error_tunnel_engine_unavailable
    }

/** La frase completa: «No se pudo conectar. …». */
@Composable
internal fun TunnelFailure.message(): String = stringResource(Res.string.error_tunnel_failed, stringResource(reason))

/** Si el fallo se arregla cambiando datos del perfil (usuario, contraseña, servidor, archivo .ovpn). */
internal val TunnelFailure.isFixedInProfile: Boolean
    get() = when (this) {
        TunnelFailure.PROXY_HOST_UNRESOLVED,
        TunnelFailure.PROXY_AUTH_FAILED,
        TunnelFailure.VPN_PROFILE_INVALID,
        TunnelFailure.VPN_SERVER_BLOCKED,
        TunnelFailure.VPN_AUTH_FAILED,
        TunnelFailure.EXIT_NODE_NOT_FOUND,
        -> true
        else -> false
    }

/** Por qué falló la búsqueda de nodos de salida de Tailscale, contado desde la pantalla de búsqueda. */
internal val TunnelFailure.exitSearchReason: StringResource
    get() = when (this) {
        TunnelFailure.VPN_AUTH_FAILED -> Res.string.error_exit_search_vpn_auth_failed
        TunnelFailure.PROXY_AUTH_FAILED -> Res.string.error_exit_search_proxy_auth_failed
        TunnelFailure.VPN_SERVER_BLOCKED -> Res.string.error_exit_search_server_blocked
        TunnelFailure.VPN_SERVER_UNREACHABLE -> Res.string.error_exit_search_server_unreachable
        TunnelFailure.ENGINE_UNAVAILABLE -> Res.string.error_exit_search_engine_unavailable
        TunnelFailure.VPN_PROFILE_INVALID -> Res.string.error_exit_search_invalid_server
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
}
