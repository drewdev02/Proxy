package org.uci.proxy.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.uci.proxy.core.port.KeyValueStore
import kotlin.time.Clock

/** Qué pasó la última vez que se pidió un destino al proxy con el nombre de un servicio. */
enum class NameOutcome {
    /** El proxy abrió el túnel. */
    WORKED,

    /** El proxy rechazó el nombre (403 o similar). */
    REFUSED,
}

@Serializable
data class NameServiceStatus(val outcome: NameOutcome, val atMillis: Long)

/** Quien se entera de cómo le fue a cada servicio: el túnel avisa y la pantalla de Ajustes lo muestra. */
fun interface NameServiceReporter {
    fun report(serviceId: String, outcome: NameOutcome)

    companion object {
        val None = NameServiceReporter { _, _ -> }
    }
}

interface NameServiceStatusRepository : NameServiceReporter {
    /** El último resultado de cada servicio, por id. */
    val statuses: StateFlow<Map<String, NameServiceStatus>>
}

/**
 * Recuerda el último resultado de cada servicio, también entre arranques. Para no escribir en disco con cada conexión,
 * un resultado igual al anterior y reciente solo refresca la hora cada [MIN_REFRESH_MS].
 */
class StoredNameServiceStatusRepository(
    private val keyValueStore: KeyValueStore,
    private val clock: Clock,
) : NameServiceStatusRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    override val statuses: StateFlow<Map<String, NameServiceStatus>> = state.asStateFlow()

    override fun report(serviceId: String, outcome: NameOutcome) {
        val now = clock.now().toEpochMilliseconds()
        val previous = state.value[serviceId]
        if (previous != null && previous.outcome == outcome && now - previous.atMillis < MIN_REFRESH_MS) return
        val updated = state.value + (serviceId to NameServiceStatus(outcome, now))
        keyValueStore.putString(KEY, json.encodeToString(updated))
        state.value = updated
    }

    private fun load(): Map<String, NameServiceStatus> {
        val stored = keyValueStore.getString(KEY) ?: return emptyMap()
        return try {
            json.decodeFromString(stored)
        } catch (_: SerializationException) {
            emptyMap()
        }
    }

    internal companion object {
        const val KEY = "name_service_status"
        const val MIN_REFRESH_MS = 60_000L
    }
}
