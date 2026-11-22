package org.uci.proxy.core.proxy

import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.model.ProxyProfile
import kotlin.time.Duration

sealed interface ProxyTestResult {
    /** El proxy aceptó las credenciales y abrió un túnel de prueba. */
    data object Success : ProxyTestResult

    data class Failure(val failure: ProxyFailure) : ProxyTestResult

    data object Timeout : ProxyTestResult
}

interface ProxyTester {
    suspend fun test(profile: ProxyProfile, password: String): ProxyTestResult
}

/**
 * Prueba un perfil abriendo de verdad un túnel hacia [target] y cerrándolo enseguida.
 *
 * @param connectors uno por protocolo; se usa el que coincide con el perfil.
 */
class ConnectingProxyTester(
    private val connectors: List<ProxyConnector>,
    private val target: ProxyTarget,
    private val timeout: Duration,
) : ProxyTester {
    override suspend fun test(profile: ProxyProfile, password: String): ProxyTestResult {
        val connector = connectors.firstOrNull { it.protocol == profile.protocol }
            ?: return ProxyTestResult.Failure(ProxyFailure.ProtocolError(ProtocolIssue.NO_CONNECTOR, "Protocolo ${profile.protocol} no disponible"))

        val result = withTimeoutOrNull(timeout) { connector.connect(profile, password, target) }
        return when (result) {
            null -> ProxyTestResult.Timeout
            is ProxyConnectResult.Failed -> ProxyTestResult.Failure(result.failure)
            is ProxyConnectResult.Connected -> {
                result.connection.close()
                ProxyTestResult.Success
            }
        }
    }
}
