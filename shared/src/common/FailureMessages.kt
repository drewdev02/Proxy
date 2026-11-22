package org.uci.proxy.common

import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.vpn.OvpnConfigError

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
    TunnelFailure.VPN_PROFILE_INVALID ->
        "la configuración de Otro País no sirve (el archivo .ovpn debe ser OpenVPN sobre TCP; " +
            "la dirección del servidor de Tailscale debe ser válida). Revísala en el perfil."
    TunnelFailure.VPN_SERVER_UNREACHABLE -> "el proxy no dejó llegar al servidor de Otro País."
    TunnelFailure.VPN_SERVER_BLOCKED ->
        "la política del proxy bloqueó el acceso a los servidores de Otro País. Con OpenVPN, prueba con otro servidor, uno con puerto 443."
    TunnelFailure.VPN_AUTH_FAILED ->
        "Otro País rechazó las credenciales (usuario y contraseña de la VPN, o clave de Tailscale)."
    TunnelFailure.VPN_HANDSHAKE_FAILED -> "el servidor de Otro País no completó la conexión."
    TunnelFailure.EXIT_NODE_NOT_FOUND ->
        "el nodo de salida de Tailscale no existe en la red, o hay varios y no se eligió ninguno."
    TunnelFailure.ENGINE_UNAVAILABLE -> "el motor de Otro País del perfil no está disponible en este equipo."
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
        TunnelFailure.EXIT_NODE_NOT_FOUND,
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

/** Por qué no sirve el archivo `.ovpn` elegido. */
internal fun OvpnConfigError.message(): String = when (this) {
    is OvpnConfigError.UnclosedBlock -> "El archivo está incompleto: falta cerrar <$block>."
    OvpnConfigError.NotAClient -> "El archivo no es un perfil de cliente OpenVPN."
    OvpnConfigError.TapNotSupported -> "Solo se admiten perfiles de tipo tun, no tap."
    OvpnConfigError.NoTcpServers ->
        "El perfil no tiene servidores TCP. A través de un proxy solo funciona OpenVPN sobre TCP: descarga la variante TCP del perfil."
    OvpnConfigError.TlsAuthNotSupported -> "El perfil usa tls-auth, que todavía no se admite (sí tls-crypt)."
    OvpnConfigError.TlsCryptV2NotSupported -> "El perfil usa tls-crypt-v2, que todavía no se admite (sí tls-crypt)."
    OvpnConfigError.ClientCertificateNotSupported ->
        "El perfil usa certificado de cliente, que todavía no se admite. Solo usuario y contraseña."
    is OvpnConfigError.CompressionNotSupported -> "El perfil usa compresión ($algorithm), que no se admite."
    OvpnConfigError.MissingCa -> "El perfil no incluye el certificado de la autoridad (<ca>)."
    OvpnConfigError.MissingTlsCrypt -> "El perfil no incluye <tls-crypt>, que es lo único que se admite."
    OvpnConfigError.BadTlsCryptKey -> "La clave de <tls-crypt> no tiene el formato esperado."
}
