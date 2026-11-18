package org.uci.proxy.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.uci.proxy.core.port.KeyValueStore

/**
 * Los ajustes que elige el usuario. Viajan al motor al conectar (en escritorio corre en otro proceso), así que un
 * cambio se aplica a partir de la siguiente conexión.
 */
@Serializable
data class AppSettings(
    /**
     * El túnel contesta por su cuenta las comprobaciones de conectividad de Android, Windows y macOS, que el proxy suele
     * bloquear y hacen que el sistema muestre «sin internet» aunque lo haya. Solo contesta si el proxy funciona.
     */
    val answerConnectivityChecks: Boolean = true,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setAnswerConnectivityChecks(enabled: Boolean)
}

/** Guarda cada ajuste como una clave del [KeyValueStore]; lo que falte o no se entienda toma el valor por defecto. */
class StoredSettingsRepository(private val keyValueStore: KeyValueStore) : SettingsRepository {
    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun setAnswerConnectivityChecks(enabled: Boolean) {
        keyValueStore.putString(ANSWER_CONNECTIVITY_CHECKS_KEY, enabled.toString())
        state.value = state.value.copy(answerConnectivityChecks = enabled)
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            answerConnectivityChecks = keyValueStore.getString(ANSWER_CONNECTIVITY_CHECKS_KEY)?.toBooleanStrictOrNull()
                ?: defaults.answerConnectivityChecks,
        )
    }

    internal companion object {
        const val ANSWER_CONNECTIVITY_CHECKS_KEY = "settings.answer_connectivity_checks"
    }
}
