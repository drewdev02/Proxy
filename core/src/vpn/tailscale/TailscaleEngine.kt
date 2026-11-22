package org.uci.proxy.core.vpn.tailscale

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.vpn.CountryEngine
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.ExitContext
import org.uci.proxy.core.vpn.ProxyDialResult
import org.uci.proxy.core.vpn.ProxyDialer
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnNetwork
import org.uci.proxy.core.vpn.VpnSession
import kotlin.coroutines.coroutineContext

/** Un nodo de la red de Tailscale que ofrece salida. */
@Serializable
data class TailscaleExitNode(
    val id: String,
    val name: String,
    val dnsName: String = "",
    val ip: String = "",
    val online: Boolean = false,
    val country: String = "",
    val countryCode: String = "",
    val city: String = "",
) {
    /** Lo que se guarda en el perfil para elegirlo: el nombre, que sobrevive a cambios de IP. */
    val selector: String get() = name.ifEmpty { ip }

    val place: String get() = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ")
}

/** El resultado de buscar los nodos de salida de una red. */
sealed interface ExitNodeListing {
    data class Found(val nodes: List<TailscaleExitNode>) : ExitNodeListing

    data class Failed(val failure: TunnelFailure, val detail: String) : ExitNodeListing
}

/**
 * Motor Tailscale: un nodo de una red de Tailscale (o de un servidor Headscale) que saca el tráfico por un nodo de
 * salida de esa red. Todo lo que el nodo abre hacia fuera pasa por un [LocalConnectProxy] que sale por el proxy del perfil;
 * los paquetes van y vienen por la librería nativa ([TailscaleBridge]).
 *
 * Detrás del proxy no hay UDP, así que todo viaja por los relés DERP de Tailscale (TCP 443): funciona, con algo más de
 * latencia que una conexión directa.
 */
class TailscaleEngine(
    private val bridge: TailscaleBridge,
    private val dialer: ProxyDialer,
    private val selector: SelectorManager,
    private val random: RandomBytes,
    private val logger: Logger,
    /** Para las llamadas bloqueantes a la librería. */
    private val io: CoroutineDispatcher,
    private val startTimeoutMs: Int = 60_000,
) : CountryEngine {
    override val kind = CountryEngineKind.TAILSCALE
    override val isAvailable: Boolean get() = bridge.isAvailable

    override suspend fun connect(context: ExitContext, start: CountryExitStart): VpnConnectResult {
        val exit = start.exit as? CountryExit.Tailscale
        val secrets = start.secrets as? CountryExitSecrets.Tailscale
        if (exit == null || secrets == null) {
            logger.log(LogLevel.ERROR, TAG, "La salida no es de Tailscale")
            return VpnConnectResult.Failed(TunnelFailure.VPN_PROFILE_INVALID)
        }
        val stateDir = start.stateDir
        if (stateDir == null) {
            logger.log(LogLevel.ERROR, TAG, "Esta plataforma no dio un directorio para la identidad del nodo")
            return VpnConnectResult.Failed(TunnelFailure.ENGINE_UNAVAILABLE)
        }
        val proxy = LocalConnectProxy(selector, dialer.session(context), logger, random, io)
        proxy.start()
        logger.log(LogLevel.INFO, TAG, "Conectando con la red de Tailscale (${exit.controlUrl}) a través del proxy")
        val config = BridgeConfig(
            controlUrl = exit.controlUrl,
            authKey = secrets.authKey,
            hostname = exit.hostname,
            stateDir = stateDir,
            exitNode = exit.exitNode,
            proxyUrl = proxy.url,
            tempDir = bridge.tempDir,
            timeoutMs = startTimeoutMs,
        )
        val reply = try {
            startBridge(config, proxy)
        } catch (e: Exception) {
            // Sin esto el proxy local quedaría abierto; el fallo lo trata CountryEngines.
            proxy.close()
            throw e
        }
        val started = parse<StartReply>(reply)
        drainLogs()
        val handle = started?.handle
        val network = started?.network
        if (handle == null || network == null) {
            proxy.close()
            val error = started?.error ?: BridgeError("INTERNAL", "respuesta ilegible de la librería")
            val failure = failureOf(error, proxy)
            logger.log(LogLevel.WARN, TAG, "Tailscale no conectó: ${error.message}")
            return VpnConnectResult.Failed(failure)
        }
        logger.log(LogLevel.INFO, TAG, "En la red de Tailscale como ${network.address}, saliendo por ${network.exitNode?.name ?: "?"}")
        return VpnConnectResult.Connected(
            TailscaleSession(
                handle = handle,
                network = VpnNetwork(
                    address = network.address,
                    // Toda la red de Tailscale (100.64.0.0/10) va por la interfaz: ahí viven el DNS 100.100.100.100 y
                    // los demás nodos.
                    prefixLength = TAILNET_PREFIX,
                    dnsServers = network.dns,
                    mtu = network.mtu,
                ),
                proxy = proxy,
            ),
        )
    }

    /**
     * Busca los nodos de salida de la red con un nodo efímero, que la red olvida al terminar: así el editor del perfil
     * puede ofrecerlos sin dejar un equipo registrado por cada búsqueda.
     */
    suspend fun listExitNodes(context: ExitContext, exit: CountryExit.Tailscale, secrets: CountryExitSecrets.Tailscale): ExitNodeListing {
        if (!bridge.isAvailable) return ExitNodeListing.Failed(TunnelFailure.ENGINE_UNAVAILABLE, "Tailscale no está disponible en este equipo")
        val proxy = LocalConnectProxy(selector, dialer.session(context), logger, random, io)
        proxy.start()
        try {
            val config = BridgeConfig(
                controlUrl = exit.controlUrl,
                authKey = secrets.authKey,
                hostname = exit.hostname + "-busqueda",
                proxyUrl = proxy.url,
                listOnly = true,
                ephemeral = true,
                tempDir = bridge.tempDir,
                timeoutMs = startTimeoutMs,
            )
            val started = parse<StartReply>(startBridge(config, proxy))
            drainLogs()
            val handle = started?.handle
            if (handle == null) {
                val error = started?.error ?: BridgeError("INTERNAL", "respuesta ilegible de la librería")
                logger.log(LogLevel.WARN, TAG, "No se pudieron buscar los nodos de salida (${error.code}): ${error.message}")
                return ExitNodeListing.Failed(failureOf(error, proxy), error.message)
            }
            try {
                val listed = parse<ExitNodesReply>(withContext(io) { bridge.exitNodes(handle) })
                val error = listed?.error
                return if (listed?.exitNodes != null && error == null) {
                    ExitNodeListing.Found(listed.exitNodes.sortedWith(compareBy({ !it.online }, { it.name })))
                } else {
                    ExitNodeListing.Failed(TunnelFailure.VPN_HANDSHAKE_FAILED, error?.message ?: "respuesta ilegible")
                }
            } finally {
                withContext(io) { bridge.close(handle) }
            }
        } finally {
            proxy.close()
        }
    }

    /**
     * Arranca la sesión en la librería. El arranque bloquea un hilo hasta que el control responde; se corta antes si el
     * proxy del perfil rechaza sus credenciales (no va a mejorar) o si se cancela quien espera (al desconectar).
     */
    private suspend fun startBridge(config: BridgeConfig, proxy: LocalConnectProxy): String = coroutineScope {
        val call = async(io) { bridge.start(json.encodeToString(BridgeConfig.serializer(), config)) }
        val watcher = launch {
            while (isActive) {
                delay(200)
                if (proxy.authFailures > 0) {
                    logger.log(LogLevel.WARN, TAG, "El proxy rechazó las credenciales del perfil: no se sigue intentando")
                    bridge.cancelStart()
                    break
                }
            }
        }
        try {
            call.await()
        } catch (e: CancellationException) {
            bridge.cancelStart()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun failureOf(error: BridgeError, proxy: LocalConnectProxy): TunnelFailure = when (error.code) {
        "AUTH_FAILED" -> TunnelFailure.VPN_AUTH_FAILED
        "EXIT_NODE_NOT_FOUND" -> TunnelFailure.EXIT_NODE_NOT_FOUND
        "CONFIG_INVALID" -> TunnelFailure.VPN_PROFILE_INVALID
        // Sin llegar al control, lo que dijo el proxy del perfil explica por qué.
        "CONTROL_UNREACHABLE" -> when (proxy.worstFailure) {
            ProxyDialResult.Reason.PROXY_AUTH -> TunnelFailure.PROXY_AUTH_FAILED
            ProxyDialResult.Reason.REFUSED -> if (proxy.connected == 0) TunnelFailure.VPN_SERVER_BLOCKED else TunnelFailure.VPN_SERVER_UNREACHABLE
            else -> TunnelFailure.VPN_SERVER_UNREACHABLE
        }
        else -> TunnelFailure.VPN_HANDSHAKE_FAILED
    }

    /** Pasa al registro de la app lo que la librería haya contado. */
    private fun drainLogs() {
        while (true) {
            val line = bridge.nextLog(0) ?: return
            logger.log(LogLevel.INFO, TAG, line)
        }
    }

    private inner class TailscaleSession(
        private val handle: Long,
        override val network: VpnNetwork,
        private val proxy: LocalConnectProxy,
    ) : VpnSession {
        @kotlin.concurrent.Volatile
        private var closed = false

        override suspend fun writePacket(packet: ByteArray) {
            if (!closed) bridge.write(handle, packet, packet.size)
        }

        override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) = coroutineScope {
            val logs = launch(io) {
                while (isActive && !closed) {
                    val line = bridge.nextLog(LOG_POLL_MS) ?: continue
                    logger.log(LogLevel.INFO, TAG, line)
                }
            }
            val buffer = ByteArray(MAX_PACKET)
            try {
                while (coroutineContext.isActive && !closed) {
                    val n = withContext(io) { bridge.read(handle, buffer, READ_POLL_MS) }
                    if (n < 0) break
                    if (n > 0) onPacket(buffer.copyOf(n))
                }
            } finally {
                logs.cancel()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            bridge.close(handle)
            proxy.close()
        }
    }

    @Serializable
    private data class BridgeConfig(
        val controlUrl: String,
        val authKey: String,
        val hostname: String,
        val stateDir: String = "",
        val exitNode: String = "",
        val proxyUrl: String,
        val listOnly: Boolean = false,
        val ephemeral: Boolean = false,
        val tempDir: String = "",
        val timeoutMs: Int,
    )

    @Serializable
    private data class BridgeError(val code: String, val message: String)

    @Serializable
    private data class BridgeNetwork(
        val address: String,
        val prefix: Int = 32,
        val dns: List<String> = emptyList(),
        val mtu: Int = 1280,
        val exitNode: TailscaleExitNode? = null,
    )

    @Serializable
    private data class StartReply(val handle: Long? = null, val network: BridgeNetwork? = null, val error: BridgeError? = null)

    @Serializable
    private data class ExitNodesReply(
        @SerialName("exitNodes") val exitNodes: List<TailscaleExitNode>? = null,
        val error: BridgeError? = null,
    )

    private inline fun <reified T> parse(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TAG = "Tailscale"
        const val TAILNET_PREFIX = 10
        const val MAX_PACKET = 65_535
        const val READ_POLL_MS = 500
        const val LOG_POLL_MS = 500
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
