package org.uci.proxy.core.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.vpn.VpnStart

/*
 * Protocolo entre la ventana de escritorio y el proceso del motor, que corre con privilegios
 * porque crear la interfaz de red lo exige. Van por un socket local, una línea de texto por mensaje:
 *
 *   motor   -> ventana : <token>                    se identifica con el token que recibió al arrancar
 *   ventana -> motor   : START <json de EngineStart>
 *   motor   -> ventana : STATE connected | STATE disconnected | STATE failed <TunnelFailure>
 *   motor   -> ventana : LOG <json de EngineLog>    un mensaje de registro, para la pantalla «Registro» de la ventana
 *   motor   -> ventana : NAMESTAT <id del servicio> WORKED | REFUSED   cómo le fue a un servicio de nombres para IP
 *   ventana -> motor   : STOP
 *
 * Si cualquiera de los dos cierra el socket, el otro da el túnel por terminado. Así el motor nunca
 * queda huérfano con privilegios si la ventana se cierra.
 */

@Serializable
data class EngineStart(
    val profile: ProxyProfile,
    val password: String,
    val settings: AppSettings = AppSettings(),
    /** Si el perfil sale por una VPN, el archivo y las credenciales de esta. */
    val vpn: VpnStart? = null,
)

/** Un mensaje de registro del motor. El nivel viaja por su nombre: un nivel que la ventana no conozca no la rompe. */
@Serializable
data class EngineLog(val epochMillis: Long, val level: String, val tag: String, val message: String)

object EngineProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    const val STOP = "STOP"
    private const val START_PREFIX = "START "
    private const val STATE_PREFIX = "STATE "
    private const val LOG_PREFIX = "LOG "

    private const val NAMESTAT_PREFIX = "NAMESTAT "

    fun encodeNameStat(serviceId: String, outcome: NameOutcome): String = "$NAMESTAT_PREFIX$serviceId ${outcome.name}"

    /** @return el servicio y el resultado, o null si [line] no es un NAMESTAT válido. */
    fun decodeNameStat(line: String): Pair<String, NameOutcome>? {
        if (!line.startsWith(NAMESTAT_PREFIX)) return null
        val parts = line.removePrefix(NAMESTAT_PREFIX).split(' ')
        if (parts.size != 2 || parts[0].isEmpty()) return null
        val outcome = NameOutcome.entries.firstOrNull { it.name == parts[1] } ?: return null
        return parts[0] to outcome
    }

    /** El JSON escapa los saltos de línea, así que un mensaje de varias líneas sigue siendo un solo mensaje del protocolo. */
    fun encodeLog(entry: LogEntry): String = LOG_PREFIX + json.encodeToString(
        EngineLog.serializer(),
        EngineLog(entry.epochMillis, entry.level.name, entry.tag, entry.message),
    )

    /** @return null si [line] no es un LOG válido. */
    fun decodeLog(line: String): EngineLog? {
        if (!line.startsWith(LOG_PREFIX)) return null
        return try {
            json.decodeFromString(EngineLog.serializer(), line.removePrefix(LOG_PREFIX))
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    fun encodeStart(start: EngineStart): String = START_PREFIX + json.encodeToString(EngineStart.serializer(), start)

    /** @return null si [line] no es un START válido. */
    fun decodeStart(line: String): EngineStart? {
        if (!line.startsWith(START_PREFIX)) return null
        return try {
            json.decodeFromString(EngineStart.serializer(), line.removePrefix(START_PREFIX))
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /** Solo se transmiten los estados que produce el motor; "conectando" lo pone la propia ventana. */
    fun encodeState(state: TunnelState): String? = when (state) {
        is TunnelState.Connected -> STATE_PREFIX + "connected"
        TunnelState.Disconnected -> STATE_PREFIX + "disconnected"
        is TunnelState.Failed -> STATE_PREFIX + "failed " + state.failure.name
        is TunnelState.Connecting -> null
    }

    /** @param profileId el perfil con el que la ventana pidió conectar. */
    fun decodeState(line: String, profileId: String): TunnelState? {
        if (!line.startsWith(STATE_PREFIX)) return null
        val parts = line.removePrefix(STATE_PREFIX).split(' ')
        return when (parts[0]) {
            "connected" -> TunnelState.Connected(profileId)
            "disconnected" -> TunnelState.Disconnected
            "failed" -> TunnelFailure.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let(TunnelState::Failed)
            else -> null
        }
    }
}
