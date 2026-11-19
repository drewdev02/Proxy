package org.uci.proxy.core.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.settings.NameServiceReporter
import org.uci.proxy.core.settings.SettingsRepository
import org.uci.proxy.core.vpn.VpnStart
import java.io.IOException
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Lado de la ventana en escritorio: lanza el motor con privilegios, le pasa el perfil por un socket
 * local y refleja el estado que el motor va comunicando.
 *
 * @param launchTimeout cuánto se espera a que el motor se presente; incluye el tiempo que la persona
 *   tarde en escribir su contraseña de administrador.
 */
class DesktopTunnelController(
    private val profiles: ProfileRepository,
    private val launcher: EngineLauncher,
    private val randomBytes: RandomBytes,
    dispatchers: AppDispatchers,
    private val logger: Logger,
    /** Donde la ventana junta sus mensajes de registro con los que le manda el motor. */
    private val logBuffer: LogBuffer,
    /** Los ajustes que se llevan al motor al conectar: él corre en otro proceso y no puede leerlos. */
    private val settings: SettingsRepository,
    /** Donde la ventana anota cómo le fue a cada servicio de nombres, según cuenta el motor. */
    private val nameStatus: NameServiceReporter,
    private val launchTimeout: Duration = 3.minutes,
) : TunnelControl, TunnelMonitor {
    override val isAvailable = true

    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.background)
    private var session: Job? = null

    // Lo que hay que cerrar para detener la sesión en curso. Lo tocan la corrutina de sesión y quien desconecta.
    @Volatile
    private var engineSocket: Socket? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopRequested = false

    override suspend fun connect(profileId: String) {
        if (session?.isActive == true) return
        val profile = profiles.profiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            mutableState.value = TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND)
            return
        }
        val vpn = profile.vpn?.let { vpnProfile ->
            val secrets = profiles.vpnSecrets(profileId)
            if (secrets == null) {
                logger.log(LogLevel.ERROR, TAG, "El perfil tiene una VPN pero no se pudo leer su archivo")
                mutableState.value = TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID)
                return
            }
            VpnStart(secrets.ovpn, vpnProfile.username, secrets.password)
        }
        val start = EngineStart(profile, profiles.password(profileId), settings.settings.value, vpn)
        mutableState.value = TunnelState.Connecting(profileId)
        stopRequested = false
        session = scope.launch { runSession(profileId, start) }
    }

    override suspend fun disconnect() {
        stopRequested = true
        // Con el motor ya conectado se le pide parar; si aún se está esperando, se deja de esperar.
        try {
            engineSocket?.let { PrintWriter(it.getOutputStream(), true).println(EngineProtocol.STOP) }
        } catch (_: IOException) {
            // El motor ya se había ido.
        }
        closeQuietly(serverSocket)
    }

    private fun runSession(profileId: String, start: EngineStart) {
        var failure: TunnelFailure? = null
        val token = randomBytes.next(TOKEN_BYTES).joinToString("") { "%02x".format(it) }
        val tokenFile = PrivateFile.createTemp("proxyuci-", ".token")
        var engine: EngineProcess? = null
        try {
            Files.writeString(tokenFile, token)
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                serverSocket = server
                val launched = launcher.launch(server.localPort, tokenFile)
                engine = launched
                val socket = awaitEngine(server, launched)
                if (socket == null) {
                    // El motor no llegó a presentarse: la persona canceló la contraseña o el arranque falló.
                    if (!stopRequested) failure = TunnelFailure.VPN_PERMISSION_DENIED
                    return
                }
                socket.use {
                    engineSocket = it
                    failure = converse(it, token, profileId, start)
                }
            }
        } catch (e: IOException) {
            logger.log(LogLevel.ERROR, TAG, "Fallo hablando con el motor: ${e.message}")
            if (!stopRequested) failure = TunnelFailure.INTERFACE_UNAVAILABLE
        } finally {
            engineSocket = null
            serverSocket = null
            Files.deleteIfExists(tokenFile)
            engine?.destroy()
            mutableState.value = failure?.let(TunnelState::Failed) ?: TunnelState.Disconnected
        }
    }

    /** Espera a que el motor se conecte, mientras su proceso siga vivo y no se agote el plazo. */
    private fun awaitEngine(server: ServerSocket, engine: EngineProcess): Socket? {
        server.soTimeout = ACCEPT_POLL_MS
        val started = TimeSource.Monotonic.markNow()
        while (!stopRequested && started.elapsedNow() < launchTimeout) {
            try {
                return server.accept()
            } catch (_: SocketTimeoutException) {
                if (!engine.isAlive) return null
            } catch (_: IOException) {
                return null // se cerró el socket al desconectar
            }
        }
        return null
    }

    /** @return el fallo que comunicó el motor, o null si la sesión terminó con normalidad. */
    private fun converse(socket: Socket, token: String, profileId: String, start: EngineStart): TunnelFailure? {
        val reader = socket.getInputStream().bufferedReader()
        val writer = PrintWriter(socket.getOutputStream().bufferedWriter(), true)

        // Cualquier proceso local puede conectarse al puerto: solo se confía en quien presenta el token.
        if (reader.readLine() != token) {
            logger.log(LogLevel.ERROR, TAG, "Conexión local rechazada: token incorrecto")
            return TunnelFailure.INTERFACE_UNAVAILABLE
        }
        writer.println(EngineProtocol.encodeStart(start))

        var failure: TunnelFailure? = null
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                null // el motor no cerró tras avisar de que terminó: se da por acabado
            } ?: break
            // Los mensajes de registro del motor se juntan con los de la ventana, para la pantalla «Registro».
            EngineProtocol.decodeLog(line)?.let { log ->
                val level = LogLevel.entries.firstOrNull { it.name == log.level } ?: LogLevel.INFO
                logBuffer.append(log.epochMillis, level, log.tag, log.message)
                continue
            }
            EngineProtocol.decodeNameStat(line)?.let { (serviceId, outcome) ->
                nameStatus.report(serviceId, outcome)
                continue
            }
            when (val state = EngineProtocol.decodeState(line, profileId)) {
                is TunnelState.Connected -> mutableState.value = state
                is TunnelState.Failed -> failure = state.failure
                // El motor publica este estado en cuanto el túnel acaba, y aún le quedan por enviar los últimos mensajes de
                // registro (el cierre, un error al apagar). Se sigue leyendo hasta que cierre el socket, con un tope.
                TunnelState.Disconnected -> socket.soTimeout = DRAIN_TIMEOUT_MS
                else -> Unit
            }
        }
        return failure
    }

    private fun closeQuietly(server: ServerSocket?) {
        try {
            server?.close()
        } catch (_: IOException) {
            // Ya estaba cerrado.
        }
    }

    private companion object {
        const val TAG = "DesktopTunnel"
        const val TOKEN_BYTES = 32
        const val ACCEPT_POLL_MS = 500

        /** Cuánto se espera, tras el aviso de que el túnel terminó, a que el motor cierre el socket. */
        const val DRAIN_TIMEOUT_MS = 2_000
    }
}
