package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
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

    /** El proxy respondió, pero se negó a abrir el túnel hacia el destino. */
    data class Refused(val detail: String) : ProxyFailure

    /** No se pudo llegar al proxy o la conexión se cortó. */
    data class Unreachable(val detail: String) : ProxyFailure

    /** El servidor no habla el protocolo esperado. */
    data class ProtocolError(val detail: String) : ProxyFailure
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
