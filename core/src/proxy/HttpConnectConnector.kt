package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.port.TcpException

/**
 * Túnel mediante `CONNECT` de un proxy HTTP.
 *
 * La primera petición va sin credenciales: así se sabe qué esquemas ofrece el proxy y la contraseña
 * nunca se envía en claro (Basic) a un proxy que acepta NTLM.
 *
 * @param schemes esquemas disponibles, del preferido al menos preferido.
 */
class HttpConnectConnector(
    private val tcpConnector: TcpConnector,
    private val schemes: List<HttpProxyAuthScheme>,
) : ProxyConnector {
    override val protocol = ProxyProtocol.HTTP

    override suspend fun connect(profile: ProxyProfile, password: String, target: ProxyTarget): ProxyConnectResult {
        val session = HttpProxySession(tcpConnector, profile.host, profile.port, target)
        val failure = try {
            negotiate(session, ProxyCredentials(profile.username, password))
        } catch (e: TcpException) {
            if (e.kind == NetFailure.PROTOCOL) {
                ProxyFailure.ProtocolError(ProtocolIssue.NOT_HTTP_PROXY, e.message.orEmpty())
            } else {
                ProxyFailure.Unreachable(e.kind, e.message.orEmpty())
            }
        }

        return if (failure == null) {
            ProxyConnectResult.Connected(session.takeTunnel())
        } else {
            session.close()
            ProxyConnectResult.Failed(failure)
        }
    }

    private suspend fun negotiate(session: HttpProxySession, credentials: ProxyCredentials): ProxyFailure? {
        val first = session.connect(authorization = null)
        if (first.statusCode in 200..299) return null
        if (first.statusCode != 407) return ProxyFailure.Refused(RefusalReason.ofHttp(first.statusCode), first.statusLine)

        if (credentials.username.isEmpty()) return ProxyFailure.AuthRequired

        val offered = first.values("Proxy-Authenticate").map { it.substringBefore(' ').trim() }
        val scheme = schemes.firstOrNull { ours -> offered.any { it.equals(ours.name, ignoreCase = true) } }
            ?: return ProxyFailure.UnsupportedAuth(offered.distinct())

        return scheme.authenticate(session, credentials)
    }
}
