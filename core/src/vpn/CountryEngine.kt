package org.uci.proxy.core.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.settings.AppSettings

/**
 * Todo lo que el túnel necesita para salir por «Otro País»: la configuración, sus secretos y, para los motores que
 * guardan estado (la identidad de un nodo de Tailscale), dónde guardarlo. En escritorio viaja de la ventana al motor.
 */
@Serializable
data class CountryExitStart(
    val exit: CountryExit,
    val secrets: CountryExitSecrets,
    /** Directorio propio del perfil para el estado del motor, o null si el motor no guarda nada. */
    val stateDir: String? = null,
)

/**
 * El camino hasta Internet que tiene un motor: el proxy del perfil (con la dirección ya resuelta, porque con el túnel
 * activo el DNS del equipo deja de dar direcciones reales), su contraseña y los ajustes que dicen cómo pedirle al proxy
 * las IP públicas.
 */
class ExitContext(
    val proxy: ProxyProfile,
    val proxyPassword: String,
    val settings: AppSettings,
)

/**
 * Un motor de «Otro País»: abre, a través del proxy del perfil, una sesión por la que pasan paquetes IP hacia el país
 * de salida. El túnel no sabe nada más de él: ni el protocolo, ni de dónde salen la dirección o el DNS.
 */
interface CountryEngine {
    val kind: CountryEngineKind

    /** Si el motor puede usarse en este equipo (por ejemplo, si su librería nativa está presente). */
    val isAvailable: Boolean get() = true

    suspend fun connect(context: ExitContext, start: CountryExitStart): VpnConnectResult

    /** Si un fallo no se arregla reintentando (credenciales o configuración malas): el túnel deja de reintentar. */
    fun isPermanent(failure: TunnelFailure): Boolean = isPermanentFailure(failure)

    companion object {
        /** Lo que no cambia por volver a intentarlo: credenciales, configuración o algo que el servidor no admite. */
        fun isPermanentFailure(failure: TunnelFailure): Boolean = when (failure) {
            TunnelFailure.VpnAuthFailed,
            TunnelFailure.TailscaleKeyExpired,
            TunnelFailure.VpnSecretsMissing,
            is TunnelFailure.OvpnInvalid,
            TunnelFailure.TailscaleConfigInvalid,
            TunnelFailure.ProxyAuthFailed,
            TunnelFailure.ProxyAuthRequired,
            is TunnelFailure.ProxyAuthUnsupported,
            is TunnelFailure.ProxyProtocolMismatch,
            is TunnelFailure.ExitNodeNotFound,
            TunnelFailure.ExitNodeNotChosen,
            TunnelFailure.EngineUnavailable,
            -> true
            is TunnelFailure.VpnHandshakeFailed -> failure.issue in PERMANENT_HANDSHAKE_ISSUES
            else -> false
        }

        private val PERMANENT_HANDSHAKE_ISSUES = setOf(
            VpnHandshakeIssue.SERVER_CERTIFICATE,
            VpnHandshakeIssue.UNSUPPORTED_CIPHER,
            VpnHandshakeIssue.UNSUPPORTED_SERVER,
            VpnHandshakeIssue.UNAVAILABLE,
        )
    }
}

/** Los motores de esta plataforma. */
class CountryEngines(engines: List<CountryEngine>, private val logger: Logger? = null) {
    private val byKind = engines.associateBy { it.kind }

    /** El motor de [kind] si existe y puede usarse aquí. */
    operator fun get(kind: CountryEngineKind): CountryEngine? = byKind[kind]?.takeIf { it.isAvailable }

    /** Los motores que pueden usarse aquí, en el orden de [CountryEngineKind]. */
    val available: List<CountryEngineKind> get() = CountryEngineKind.entries.filter { get(it) != null }

    /** Abre la sesión con el motor de la salida, o falla con un motivo claro si no está disponible. */
    suspend fun connect(context: ExitContext, start: CountryExitStart): VpnConnectResult {
        val engine = get(start.exit.kind) ?: return VpnConnectResult.Failed(TunnelFailure.EngineUnavailable)
        // Un motor que lanza no debe tumbar el túnel: quien llama solo sabe tratar Failed (caer al proxy o reintentar).
        return try {
            engine.connect(context, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.log(LogLevel.WARN, TAG, "El motor ${start.exit.kind.tag} falló al conectar: ${e.message}")
            VpnConnectResult.Failed(TunnelFailure.EngineInternal)
        }
    }

    fun isPermanent(kind: CountryEngineKind, failure: TunnelFailure): Boolean =
        byKind[kind]?.isPermanent(failure) ?: CountryEngine.isPermanentFailure(failure)

    companion object {
        private const val TAG = "Vpn"
        val None = CountryEngines(emptyList())
    }
}
