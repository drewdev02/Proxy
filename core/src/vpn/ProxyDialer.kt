package org.uci.proxy.core.vpn

import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.proxy.IpNamer
import org.uci.proxy.core.proxy.NameAttempt
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.settings.NameServiceReporter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Cómo terminó un intento de llegar a un destino a través del proxy. */
sealed interface ProxyDialResult {
    class Connected(val connection: TcpConnection) : ProxyDialResult

    data class Failed(val reason: Reason, val detail: String) : ProxyDialResult

    enum class Reason {
        /** El proxy rechazó el usuario o la contraseña: ningún otro destino va a funcionar. */
        PROXY_AUTH,

        /** El proxy respondió, pero su política no deja llegar a ese destino (403 o similar). */
        REFUSED,

        /** No se llegó: el proxy no contestó, no alcanzó el destino o pasó el tiempo. */
        UNREACHABLE,
    }
}

/**
 * Llega a `host:puerto` a través del proxy de un perfil, con lo que la app aprendió de los proxies institucionales: una IP
 * pública se pide por su nombre en un servicio como sslip.io (el Squid de la UCI rechaza las IP tal cual), probando cada
 * servicio activo por orden y apartando los que el proxy rechaza; y se distingue «bloqueado por política» de
 * «inalcanzable» y de «credenciales del proxy rechazadas».
 *
 * Lo usan todos los motores de «Otro País» para llegar a sus servidores: así todos pasan el proxy de la misma forma.
 */
class ProxyDialer(
    private val connectors: List<ProxyConnector>,
    private val logger: Logger,
    /** A quién se avisa de qué servicio de nombres funcionó o fue rechazado, para mostrarlo en Ajustes. */
    private val reporter: NameServiceReporter = NameServiceReporter.None,
    private val connectTimeout: Duration = 20.seconds,
) {
    /** Una tanda de conexiones con el mismo contexto: recuerda qué servicio de nombres funcionó la última vez. */
    fun session(context: ExitContext): Session = Session(context, IpNamer(context.settings, reporter))

    inner class Session internal constructor(private val context: ExitContext, private val namer: IpNamer) {
        suspend fun dial(host: String, port: Int): ProxyDialResult {
            val connector = connectors.firstOrNull { it.protocol == context.proxy.protocol }
                ?: return ProxyDialResult.Failed(ProxyDialResult.Reason.UNREACHABLE, "sin conector para ${context.proxy.protocol}")
            var result: ProxyConnectResult? = null
            var used: NameAttempt? = null
            for (attempt in namer.attempts(host)) {
                logger.log(
                    LogLevel.DEBUG, TAG,
                    "Conectando con $host:$port a través del proxy" + if (attempt.host != host) " (vía ${attempt.host})" else "",
                )
                result = withTimeoutOrNull(connectTimeout) {
                    connector.connect(context.proxy, context.proxyPassword, ProxyTarget(attempt.host, port))
                }
                used = attempt
                if (result is ProxyConnectResult.Connected) break
                if ((result as? ProxyConnectResult.Failed)?.failure !is ProxyFailure.Refused) break
                namer.refused(attempt)
            }
            return when (result) {
                is ProxyConnectResult.Connected -> {
                    used?.let(namer::succeeded)
                    ProxyDialResult.Connected(result.connection)
                }
                is ProxyConnectResult.Failed -> {
                    val failure = result.failure
                    val reason = when {
                        failure.isAuthentication() -> ProxyDialResult.Reason.PROXY_AUTH
                        failure is ProxyFailure.Refused -> ProxyDialResult.Reason.REFUSED
                        else -> ProxyDialResult.Reason.UNREACHABLE
                    }
                    ProxyDialResult.Failed(reason, failure.toString())
                }
                null -> ProxyDialResult.Failed(ProxyDialResult.Reason.UNREACHABLE, "tiempo agotado")
            }
        }
    }

    private fun ProxyFailure.isAuthentication() =
        this is ProxyFailure.AuthRejected || this is ProxyFailure.AuthRequired || this is ProxyFailure.UnsupportedAuth

    private companion object {
        const val TAG = "Vpn"
    }
}
