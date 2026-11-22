package org.uci.proxy.core.vpn

import kotlinx.coroutines.CancellationException
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelFailure

/**
 * Motor OpenVPN: prueba los servidores del `.ovpn` por orden (o al azar, si el perfil lo pide) hasta que uno responde.
 * Cada uno se alcanza con el [ProxyDialer], igual que el resto de motores.
 */
class OpenVpnEngine(
    private val client: OpenVpnClient,
    private val dialer: ProxyDialer,
    private val random: RandomBytes,
    private val logger: Logger,
    /** False donde no hay cliente OpenVPN (iOS): el motor existe, pero no se ofrece. */
    override val isAvailable: Boolean = true,
) : CountryEngine {
    override val kind = CountryEngineKind.OPENVPN

    override suspend fun connect(context: ExitContext, start: CountryExitStart): VpnConnectResult {
        val secrets = start.secrets as? CountryExitSecrets.OpenVpn
        val username = (start.exit as? CountryExit.OpenVpn)?.username
        if (secrets == null || username == null) {
            logger.log(LogLevel.ERROR, TAG, "La salida no es de OpenVPN")
            return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
        }
        val config = try {
            parseOvpn(secrets.ovpn)
        } catch (e: OvpnConfigException) {
            logger.log(LogLevel.ERROR, TAG, "El archivo .ovpn no sirve: ${e.message}")
            return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
        }

        // El 443 primero: es el único puerto que los proxies institucionales dejan pasar con seguridad (SuperProxy usa solo
        // ese). Dentro de cada grupo se respeta el orden del archivo, o uno al azar si el perfil lo pide.
        val ordered = if (config.remoteRandom) config.remotes.shuffled(randomSource()) else config.remotes
        val remotes = ordered.sortedBy { if (it.port == PREFERRED_PORT) 0 else 1 }

        val dial = dialer.session(context)
        var proxyRefused = false
        var handshakeFailed = false
        for (remote in remotes.take(MAX_ATTEMPTS)) {
            logger.log(LogLevel.INFO, TAG, "Conectando con el servidor VPN ${remote.host}:${remote.port} a través del proxy")
            val connection = when (val result = dial.dial(remote.host, remote.port)) {
                is ProxyDialResult.Connected -> result.connection
                is ProxyDialResult.Failed -> {
                    logger.log(LogLevel.WARN, TAG, "El proxy no abrió ${remote.host}:${remote.port}: ${result.detail}")
                    when (result.reason) {
                        ProxyDialResult.Reason.PROXY_AUTH -> return VpnConnectResult.Failed(TunnelFailure.PROXY_AUTH_FAILED)
                        ProxyDialResult.Reason.REFUSED -> proxyRefused = true
                        ProxyDialResult.Reason.UNREACHABLE -> Unit
                    }
                    continue
                }
            }
            try {
                return VpnConnectResult.Connected(client.connect(config, connection, username, secrets.password))
            } catch (e: AuthFailedException) {
                logger.log(LogLevel.ERROR, TAG, e.message.orEmpty())
                return VpnConnectResult.Failed(TunnelFailure.VPN_AUTH_FAILED)
            } catch (e: InvalidCertificateException) {
                logger.log(LogLevel.ERROR, TAG, e.message.orEmpty())
                return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // También un corte del proxy a mitad (TcpException): otro servidor puede responder, y si ninguno lo hace
                // el túnel debe caer al proxy en vez de romperse.
                logger.log(LogLevel.WARN, TAG, "Falló el handshake con ${remote.host}:${remote.port}: ${e.message}")
                handshakeFailed = true
            }
        }
        // Lo más informativo primero: si algún servidor llegó a responder, el problema es de la VPN y no del proxy.
        val failure = when {
            handshakeFailed -> TunnelFailure.VPN_HANDSHAKE_FAILED
            proxyRefused -> TunnelFailure.VPN_SERVER_BLOCKED
            else -> TunnelFailure.VPN_SERVER_UNREACHABLE
        }
        return VpnConnectResult.Failed(failure)
    }

    /** Un generador de números para mezclar la lista, alimentado por los bytes aleatorios de la plataforma. */
    private fun randomSource(): kotlin.random.Random = kotlin.random.Random(random.next(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })

    private companion object {
        const val TAG = "Vpn"
        const val MAX_ATTEMPTS = 6
        const val PREFERRED_PORT = 443
    }
}
