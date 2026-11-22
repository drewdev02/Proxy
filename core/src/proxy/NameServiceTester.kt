package org.uci.proxy.core.proxy

import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.model.IpNameService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface NameServiceTestResult {
    /** El proxy abrió un túnel pidiéndole el destino con el nombre de este servicio. */
    data object Works : NameServiceTestResult

    /** El proxy respondió pero no dejó pasar el nombre. */
    data class Refused(val reason: RefusalReason, val detail: String) : NameServiceTestResult

    /** El proxy rechazó el usuario o la contraseña del perfil: la prueba no dice nada del servicio. */
    data object ProxyAuth : NameServiceTestResult

    /** No se llegó al proxy o se cortó. */
    data class Unreachable(val kind: NetFailure, val detail: String) : NameServiceTestResult

    data object Timeout : NameServiceTestResult
}

/** Comprueba si el proxy de un perfil acepta los nombres de un servicio. */
fun interface NameServiceTester {
    suspend fun test(service: IpNameService, profile: ProxyProfile, password: String): NameServiceTestResult
}

/**
 * Pide al proxy un `CONNECT` a una IP pública conocida, por el nombre del servicio: si abre el túnel, el servicio sirve.
 * No hace falta que el destino hable nada; basta con que el proxy lo deje abrir.
 */
class ConnectingNameServiceTester(
    private val connectors: List<ProxyConnector>,
    private val timeout: Duration = 15.seconds,
    private val testIp: String = "1.1.1.1",
) : NameServiceTester {
    override suspend fun test(service: IpNameService, profile: ProxyProfile, password: String): NameServiceTestResult {
        val connector = connectors.firstOrNull { it.protocol == profile.protocol }
            ?: return NameServiceTestResult.Unreachable(NetFailure.PROTOCOL, "El perfil usa un protocolo sin conector")
        val target = ProxyTarget(service.nameFor(testIp), TEST_PORT)
        return when (val result = withTimeoutOrNull(timeout) { connector.connect(profile, password, target) }) {
            null -> NameServiceTestResult.Timeout
            is ProxyConnectResult.Connected -> {
                result.connection.close()
                NameServiceTestResult.Works
            }
            is ProxyConnectResult.Failed -> when (val failure = result.failure) {
                is ProxyFailure.Refused -> NameServiceTestResult.Refused(failure.reason, failure.detail)
                ProxyFailure.AuthRequired, ProxyFailure.AuthRejected, is ProxyFailure.UnsupportedAuth -> NameServiceTestResult.ProxyAuth
                is ProxyFailure.Unreachable -> NameServiceTestResult.Unreachable(failure.kind, failure.detail)
                is ProxyFailure.ProtocolError -> NameServiceTestResult.Unreachable(NetFailure.PROTOCOL, failure.detail)
            }
        }
    }

    private companion object {
        const val TEST_PORT = 443
    }
}
