package org.uci.proxy.core.vpn

import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Una sesión VPN lista: lo que el túnel necesita para pasar paquetes. */
interface VpnSession {
    val push: OpenVpnPush

    suspend fun writePacket(packet: ByteArray)

    /** Entrega cada paquete IP que llega de la VPN hasta que la sesión termina. */
    suspend fun receive(onPacket: suspend (ByteArray) -> Unit)

    fun close()
}

sealed interface VpnConnectResult {
    class Connected(val session: VpnSession) : VpnConnectResult

    data class Failed(val failure: TunnelFailure) : VpnConnectResult
}

/** Abre la sesión VPN de un perfil, llegando al servidor a través de su proxy. */
fun interface VpnExit {
    /** @param proxy el perfil con la dirección del proxy ya resuelta. */
    suspend fun connect(proxy: ProxyProfile, proxyPassword: String, vpn: VpnStart): VpnConnectResult
}

/**
 * Prueba los servidores del `.ovpn` por orden (o al azar, si el perfil lo pide) hasta que uno responde. Cada uno se
 * alcanza con un `CONNECT` al proxy institucional, con los mismos conectores que el resto de la app.
 */
class OpenVpnExit(
    private val client: OpenVpnClient,
    private val connectors: List<ProxyConnector>,
    private val random: RandomBytes,
    private val logger: Logger,
    private val connectTimeout: Duration = 20.seconds,
) : VpnExit {
    override suspend fun connect(proxy: ProxyProfile, proxyPassword: String, vpn: VpnStart): VpnConnectResult {
        val config = try {
            parseOvpn(vpn.ovpn)
        } catch (e: OvpnConfigException) {
            logger.log(LogLevel.ERROR, TAG, "El archivo .ovpn no sirve: ${e.message}")
            return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
        }
        val connector = connectors.firstOrNull { it.protocol == proxy.protocol }
            ?: return VpnConnectResult.Failed(TunnelFailure.VPN_SERVER_UNREACHABLE)

        val remotes = if (config.remoteRandom) config.remotes.shuffled(randomSource()) else config.remotes
        var failure = TunnelFailure.VPN_SERVER_UNREACHABLE
        for (remote in remotes.take(MAX_ATTEMPTS)) {
            logger.log(LogLevel.INFO, TAG, "Conectando con el servidor VPN ${remote.host}:${remote.port} a través del proxy")
            val result = withTimeoutOrNull(connectTimeout) { connector.connect(proxy, proxyPassword, ProxyTarget(remote.host, remote.port)) }
            val connection = when (result) {
                is ProxyConnectResult.Connected -> result.connection
                is ProxyConnectResult.Failed -> {
                    logger.log(LogLevel.WARN, TAG, "El proxy no abrió ${remote.host}:${remote.port}: ${result.failure}")
                    if (result.failure.isAuthentication()) return VpnConnectResult.Failed(TunnelFailure.PROXY_AUTH_FAILED)
                    continue
                }
                null -> {
                    logger.log(LogLevel.WARN, TAG, "Tiempo agotado conectando con ${remote.host}:${remote.port}")
                    continue
                }
            }
            try {
                return VpnConnectResult.Connected(client.connect(config, connection, vpn.username, vpn.password))
            } catch (e: AuthFailedException) {
                logger.log(LogLevel.ERROR, TAG, e.message.orEmpty())
                return VpnConnectResult.Failed(TunnelFailure.VPN_AUTH_FAILED)
            } catch (e: InvalidCertificateException) {
                logger.log(LogLevel.ERROR, TAG, e.message.orEmpty())
                return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
            } catch (e: OpenVpnException) {
                logger.log(LogLevel.WARN, TAG, "Falló el handshake con ${remote.host}:${remote.port}: ${e.message}")
                failure = TunnelFailure.VPN_HANDSHAKE_FAILED
            }
        }
        return VpnConnectResult.Failed(failure)
    }

    private fun ProxyFailure.isAuthentication() =
        this is ProxyFailure.AuthRejected || this is ProxyFailure.AuthRequired || this is ProxyFailure.UnsupportedAuth

    /** Un generador de números para mezclar la lista, alimentado por los bytes aleatorios de la plataforma. */
    private fun randomSource(): kotlin.random.Random = kotlin.random.Random(random.next(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })

    private companion object {
        const val TAG = "Vpn"
        const val MAX_ATTEMPTS = 6
    }
}

/** Para las plataformas donde el cliente VPN no está disponible (iOS): el túnel falla con un motivo claro. */
object UnavailableVpnExit : VpnExit {
    override suspend fun connect(proxy: ProxyProfile, proxyPassword: String, vpn: VpnStart) =
        VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
}
