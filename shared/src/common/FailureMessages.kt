package org.uci.proxy.common

import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTestResult

/*
 * Los textos de los fallos, en un solo sitio para que todas las pantallas digan lo mismo.
 */

/** Por qué falló la conexión del túnel, empezando en minúscula para poder ir tras «No se pudo conectar: ». */
internal fun TunnelFailure.reason(): String = when (this) {
    TunnelFailure.VPN_PERMISSION_DENIED -> "hace falta autorizar la conexión VPN."
    TunnelFailure.LOCAL_NETWORK_DENIED -> "la app necesita permiso para acceder a la red local y alcanzar el proxy."
    TunnelFailure.PROFILE_NOT_FOUND -> "el perfil ya no existe."
    TunnelFailure.INTERFACE_UNAVAILABLE -> "el sistema no permitió crear la conexión VPN."
    TunnelFailure.PROXY_HOST_UNRESOLVED -> "no se encuentra el servidor del perfil."
    TunnelFailure.PROXY_AUTH_FAILED -> "el proxy rechazó el usuario o la contraseña."
    TunnelFailure.VPN_PROFILE_INVALID -> "el archivo .ovpn del perfil no sirve. Debe ser OpenVPN sobre TCP; vuelve a elegirlo."
    TunnelFailure.VPN_SERVER_UNREACHABLE -> "el proxy no dejó llegar al servidor VPN."
    TunnelFailure.VPN_SERVER_BLOCKED ->
        "la política del proxy bloqueó el acceso a los servidores VPN del archivo. Prueba con otro servidor, uno con puerto 443."
    TunnelFailure.VPN_AUTH_FAILED -> "el servidor VPN rechazó el usuario o la contraseña."
    TunnelFailure.VPN_HANDSHAKE_FAILED -> "el servidor VPN no completó el acuerdo de claves."
}

/** La frase completa: «No se pudo conectar: …». */
internal fun TunnelFailure.message(): String = "No se pudo conectar: ${reason()}"

/** Si el fallo se arregla cambiando datos del perfil (usuario, contraseña, servidor, archivo .ovpn). */
internal val TunnelFailure.isFixedInProfile: Boolean
    get() = when (this) {
        TunnelFailure.PROXY_HOST_UNRESOLVED,
        TunnelFailure.PROXY_AUTH_FAILED,
        TunnelFailure.VPN_PROFILE_INVALID,
        TunnelFailure.VPN_SERVER_BLOCKED,
        TunnelFailure.VPN_AUTH_FAILED,
        -> true
        else -> false
    }

/** El resultado de probar el proxy, para mostrarlo tal cual. */
internal fun ProxyTestResult.message(): String = when (this) {
    ProxyTestResult.Success -> "Conexión correcta: el proxy aceptó los datos del perfil."
    ProxyTestResult.Timeout -> "El proxy no respondió a tiempo."
    is ProxyTestResult.Failure -> when (val failure = failure) {
        ProxyFailure.AuthRequired -> "El proxy pide usuario y contraseña."
        ProxyFailure.AuthRejected -> "El proxy rechazó el usuario o la contraseña."
        is ProxyFailure.UnsupportedAuth -> {
            val schemes = if (failure.schemes.isEmpty()) "" else " (${failure.schemes.joinToString()})"
            "El proxy exige un tipo de autenticación que la app no admite$schemes."
        }
        is ProxyFailure.Refused -> "El proxy respondió, pero no permitió la conexión de prueba: ${failure.detail}"
        is ProxyFailure.Unreachable -> "No se pudo conectar con el proxy. ${failure.detail}"
        is ProxyFailure.ProtocolError -> "El servidor no respondió como se esperaba. ${failure.detail}"
    }
}

/** El texto de la app cuando le falta el permiso de red local para la prueba. */
internal const val LOCAL_NETWORK_DENIED_FOR_TEST =
    "La app necesita permiso para acceder a la red local y así poder conectar con el proxy."
