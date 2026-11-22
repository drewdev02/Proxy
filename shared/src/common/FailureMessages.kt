package org.uci.proxy.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.proxy.ProxyFailure
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

/**
 * El resultado de probar el proxy, para mostrarlo tal cual. Los detalles de [ProxyFailure] son diagnóstico técnico
 * (la línea de estado HTTP, el código SOCKS, el error del sistema) y salen sin traducir, como en el registro.
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
        is ProxyFailure.Refused -> stringResource(Res.string.error_proxy_refused, failure.detail)
        is ProxyFailure.Unreachable -> stringResource(Res.string.error_proxy_unreachable, failure.detail).trimEnd()
        is ProxyFailure.ProtocolError -> stringResource(Res.string.error_proxy_protocol, failure.detail).trimEnd()
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
