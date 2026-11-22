package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.port.TcpConnection

/** Destino final al que el proxy debe conectar. */
data class ProxyTarget(val host: String, val port: Int)

sealed interface ProxyFailure {
    /** El proxy pide credenciales y el perfil no tiene. */
    data object AuthRequired : ProxyFailure

    /** El proxy rechazó el usuario o la contraseña. */
    data object AuthRejected : ProxyFailure

    /** El proxy solo ofrece esquemas de autenticación que la app no implementa. */
    data class UnsupportedAuth(val schemes: List<String>) : ProxyFailure

    /**
     * El proxy respondió, pero se negó a abrir el túnel hacia el destino.
     * @param detail lo que dijo, tal cual (la línea de estado HTTP, el código SOCKS): para el registro y los detalles.
     */
    data class Refused(val reason: RefusalReason, val detail: String) : ProxyFailure

    /** No se pudo llegar al proxy o la conexión se cortó. [detail] es el error de la plataforma, para el registro. */
    data class Unreachable(val kind: NetFailure, val detail: String) : ProxyFailure

    /** El servidor no habla el protocolo esperado. */
    data class ProtocolError(val issue: ProtocolIssue, val detail: String = "") : ProxyFailure
}

/** Por qué el proxy no abrió el túnel, según su respuesta. */
enum class RefusalReason {
    /** La política del proxy no deja llegar a ese destino (HTTP 403, SOCKS «no permitido»). */
    FORBIDDEN,

    /** El proxy no pudo llegar al destino: no existe, no responde o no hay ruta (HTTP 502, 503 y 504). */
    DESTINATION_UNREACHABLE,

    /** El destino rechazó la conexión (SOCKS «conexión rechazada»). */
    DESTINATION_REFUSED,

    /** El proxy no acepta esta petición (HTTP 400, 405 y 501, SOCKS «orden o dirección no admitida»). */
    UNSUPPORTED_REQUEST,

    OTHER,
    ;

    companion object {
        fun ofHttp(status: Int): RefusalReason = when (status) {
            403 -> FORBIDDEN
            502, 503, 504 -> DESTINATION_UNREACHABLE
            400, 405, 501 -> UNSUPPORTED_REQUEST
            else -> OTHER
        }

        /** Los códigos de respuesta de SOCKS5 (RFC 1928). */
        fun ofSocks(reply: Int): RefusalReason = when (reply) {
            2 -> FORBIDDEN
            3, 4, 6 -> DESTINATION_UNREACHABLE
            5 -> DESTINATION_REFUSED
            7, 8 -> UNSUPPORTED_REQUEST
            else -> OTHER
        }
    }
}

/** Qué no cuadra en la respuesta del servidor. */
enum class ProtocolIssue {
    /** No contesta como un proxy HTTP: quizá es SOCKS5, o no es un proxy. */
    NOT_HTTP_PROXY,

    /** No contesta como un proxy SOCKS5: quizá es HTTP, o no es un proxy. */
    NOT_SOCKS5,

    /** Contesta SOCKS5, pero con una respuesta que no se entiende. */
    BAD_SOCKS5_REPLY,

    /** El desafío NTLM del proxy no se puede leer. */
    BAD_NTLM_CHALLENGE,

    /** La app no tiene conector para el protocolo del perfil. */
    NO_CONNECTOR,
}

sealed interface ProxyConnectResult {
    /** Túnel listo: lo que se escriba en [connection] llega al destino. */
    class Connected(val connection: TcpConnection) : ProxyConnectResult

    data class Failed(val failure: ProxyFailure) : ProxyConnectResult
}

/** Abre un túnel hacia un destino a través de un proxy. Hay una implementación por protocolo. */
interface ProxyConnector {
    val protocol: ProxyProtocol

    suspend fun connect(profile: ProxyProfile, password: String, target: ProxyTarget): ProxyConnectResult
}
