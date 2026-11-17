package org.uci.proxy.core.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.ProfileRepository
import java.io.IOException
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
        val start = EngineStart(profile, profiles.password(profileId))
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
        val tokenFile = Files.createTempFile("proxyuci-", ".token", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
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
            val line = reader.readLine() ?: break
            when (val state = EngineProtocol.decodeState(line, profileId)) {
                is TunnelState.Connected -> mutableState.value = state
                is TunnelState.Failed -> failure = state.failure
                TunnelState.Disconnected -> break
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
        val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
    }
}
