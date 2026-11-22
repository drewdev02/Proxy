package org.uci.proxy.core.data

import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import org.uci.proxy.core.model.QuotaSettings
import org.uci.proxy.core.port.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.uci.proxy.core.port.KeyValueStore

/** Guarda cada ajuste como una clave del [KeyValueStore]; lo que falte o no se entienda toma el valor por defecto. */
class StoredSettingsRepository(private val keyValueStore: KeyValueStore) : SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun setAnswerConnectivityChecks(enabled: Boolean) {
        keyValueStore.putString(ANSWER_CONNECTIVITY_CHECKS_KEY, enabled.toString())
        state.value = state.value.copy(answerConnectivityChecks = enabled)
    }

    override fun setIntranetDirect(enabled: Boolean) {
        keyValueStore.putString(INTRANET_DIRECT_KEY, enabled.toString())
        state.value = state.value.copy(intranetDirect = enabled)
    }

    override fun setNameByIp(enabled: Boolean) {
        keyValueStore.putString(NAME_BY_IP_KEY, enabled.toString())
        state.value = state.value.copy(nameByIp = enabled)
    }

    override fun setTryRawIp(enabled: Boolean) {
        keyValueStore.putString(TRY_RAW_IP_KEY, enabled.toString())
        state.value = state.value.copy(tryRawIp = enabled)
    }

    override fun setNameServiceEnabled(id: String, enabled: Boolean) =
        updateServices { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    override fun moveNameService(id: String, delta: Int) = updateServices { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) {
            list
        } else {
            list.toMutableList().also { it.add(to, it.removeAt(from)) }
        }
    }

    override fun addNameService(service: IpNameService) =
        updateServices { list -> if (list.any { it.id == service.id }) list else list + service.copy(builtIn = false) }

    override fun removeNameService(id: String) = updateServices { list -> list.filterNot { it.id == id && !it.builtIn } }

    override fun resetNameServices() = updateServices { IpNameService.BUILT_IN }

    override fun updateQuota(change: (QuotaSettings) -> QuotaSettings) {
        val updated = change(state.value.quota)
        keyValueStore.putString(QUOTA_KEY, json.encodeToString(updated))
        state.value = state.value.copy(quota = updated)
    }

    private fun updateServices(transform: (List<IpNameService>) -> List<IpNameService>) {
        val updated = transform(state.value.nameServices)
        keyValueStore.putString(NAME_SERVICES_KEY, json.encodeToString(updated))
        state.value = state.value.copy(nameServices = updated)
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        fun flag(key: String, default: Boolean) = keyValueStore.getString(key)?.toBooleanStrictOrNull() ?: default
        return AppSettings(
            answerConnectivityChecks = flag(ANSWER_CONNECTIVITY_CHECKS_KEY, defaults.answerConnectivityChecks),
            intranetDirect = flag(INTRANET_DIRECT_KEY, defaults.intranetDirect),
            nameByIp = flag(NAME_BY_IP_KEY, defaults.nameByIp),
            tryRawIp = flag(TRY_RAW_IP_KEY, defaults.tryRawIp),
            nameServices = loadServices() ?: defaults.nameServices,
            quota = loadQuota() ?: defaults.quota,
        )
    }

    private fun loadQuota(): QuotaSettings? {
        val stored = keyValueStore.getString(QUOTA_KEY) ?: return null
        return try {
            json.decodeFromString<QuotaSettings>(stored)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Los guardados; si falta alguno de fábrica (porque una versión nueva lo añade) se pone al final, activo. */
    private fun loadServices(): List<IpNameService>? {
        val stored = keyValueStore.getString(NAME_SERVICES_KEY) ?: return null
        val list = try {
            json.decodeFromString<List<IpNameService>>(stored)
        } catch (_: SerializationException) {
            return null
        }
        val missing = IpNameService.BUILT_IN.filter { builtIn -> list.none { it.id == builtIn.id } }
        return list + missing
    }

    internal companion object {
        const val ANSWER_CONNECTIVITY_CHECKS_KEY = "settings.answer_connectivity_checks"
        const val INTRANET_DIRECT_KEY = "settings.intranet_direct"
        const val NAME_BY_IP_KEY = "settings.name_by_ip"
        const val TRY_RAW_IP_KEY = "settings.try_raw_ip"
        const val NAME_SERVICES_KEY = "settings.name_services"
        const val QUOTA_KEY = "settings.quota"
    }
}
