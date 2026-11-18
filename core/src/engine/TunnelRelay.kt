package org.uci.proxy.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.sniffHostname
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.port.TcpException
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.ProxyFailure
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.proxy.sslipNameFor
import org.uci.proxy.core.tcp.TunTcpConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lleva cada conexión de una app hasta su destino a través del proxy y copia los bytes en ambos sentidos.
 *
 * @param connector el del protocolo del perfil.
 */
class TunnelRelay(
    private val connector: ProxyConnector,
    private val profile: ProxyProfile,
    private val password: String,
    private val logger: Logger,
    private val health: ProxyHealth,
    private val checks: ConnectivityChecks,
    private val connectTimeout: Duration = 15.seconds,
) {
    /** Atiende una conexión de principio a fin. No lanza: cualquier fallo corta la conexión de la app. */
    suspend fun serve(app: TunTcpConnection) {
        var proxy: TcpConnection? = null
        try {
            // La app conectó a una IP sin pasar por el DNS falso: sus primeros bytes suelen decir a qué servidor va.
            val named = app.destinationHost
            val firstBytes = when {
                named == null -> withTimeoutOrNull(SNIFF_WAIT) { app.read() }
                // Una posible comprobación de conectividad: se lee la petición para ver si de verdad lo es.
                checks.mightBeCheck(named, app.destinationPort) -> withTimeoutOrNull(CHECK_WAIT) { app.read() }
                else -> null
            }
            val host = named
                ?: firstBytes?.let(::sniffHostname)
                ?: formatIpv4(app.destinationAddress)
            val target = ProxyTarget(host, app.destinationPort)

            val reply = firstBytes?.let { checks.replyFor(host, app.destinationPort, it) }
            if (reply != null) {
                logger.log(LogLevel.DEBUG, TAG, "Comprobación de conectividad contestada en local: $host")
                app.write(reply)
                app.finish()
                return
            }

            proxy = connect(target, app.destinationAddress)
            if (proxy == null) {
                app.abort()
                return
            }
            logger.log(LogLevel.DEBUG, TAG, "Túnel abierto: $host:${app.destinationPort}")

            if (firstBytes != null) proxy.write(firstBytes)
            pump(app, proxy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TcpException) {
            logger.log(LogLevel.DEBUG, TAG, "Conexión cortada: ${e.message}")
            app.abort()
        } catch (e: Exception) {
            // Frontera de cada conexión: un fallo inesperado en una no debe tumbar el túnel entero,
            // que es lo que ocurriría si la excepción llegara al ámbito de la sesión.
            logger.log(LogLevel.ERROR, TAG, "Fallo inesperado en una conexión: ${e::class.simpleName}: ${e.message}")
            app.abort()
        } finally {
            proxy?.close()
        }
    }

    private suspend fun connect(target: ProxyTarget, address: Int): TcpConnection? {
        // El Squid de la UCI rechaza los `CONNECT` a una IP pública escrita tal cual: por eso se prueba primero con el
        // nombre sslip.io equivalente, como hace SuperProxy, y solo si ese se rechaza se prueba la IP.
        val named = sslipNameFor(target.host)?.let { target.copy(host = it) }
        var result = withTimeoutOrNull(connectTimeout) { connector.connect(profile, password, named ?: target) }
        val refused = (result as? ProxyConnectResult.Failed)?.failure is ProxyFailure.Refused
        if (refused && named != null) {
            result = withTimeoutOrNull(connectTimeout) { connector.connect(profile, password, target) }
        }

        return when (result) {
            is ProxyConnectResult.Connected -> {
                health.markSuccess()
                result.connection
            }
            is ProxyConnectResult.Failed -> {
                logger.log(LogLevel.WARN, TAG, "El proxy no abrió ${target.host}:${target.port}: ${result.failure}")
                null
            }
            null -> {
                logger.log(LogLevel.WARN, TAG, "Tiempo agotado conectando con ${target.host}:${target.port}")
                null
            }
        }
    }

    /**
     * Copia en ambos sentidos hasta que los dos terminan. Cuando un lado acaba se avisa al otro
     * de que no llegarán más datos y se le da un margen para terminar de responder.
     */
    private suspend fun pump(app: TunTcpConnection, proxy: TcpConnection) = coroutineScope {
        // Si una de las dos copias falla por un error de red, coroutineScope cancela la otra y
        // relanza el error, y serve() corta entonces la conexión de la app.
        val upload = launch {
            while (true) proxy.write(app.read() ?: break)
            proxy.shutdownOutput()
        }
        val download = launch {
            while (true) app.write(proxy.read() ?: break)
            app.finish()
        }

        val uploadEndedFirst = select {
            upload.onJoin { true }
            download.onJoin { false }
        }
        if (uploadEndedFirst) {
            withTimeoutOrNull(DOWNLOAD_GRACE) { download.join() }
        } else {
            withTimeoutOrNull(UPLOAD_GRACE) { upload.join() }
        }
        upload.cancel()
        download.cancel()
        app.finish()
    }

    private companion object {
        const val TAG = "Relay"
        val SNIFF_WAIT = 200.milliseconds
        val CHECK_WAIT = 1.seconds
        val DOWNLOAD_GRACE = 8.seconds
        val UPLOAD_GRACE = 1500.milliseconds
    }
}
