package org.uci.proxy.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.uci.proxy.core.port.KeyValueStore

/**
 * Un servicio que da nombre a una IP pública. El Squid de la UCI rechaza los `CONNECT` a una IP escrita tal cual, pero
 * acepta el mismo destino por nombre; estos servicios (sslip.io, nip.io…) responden con esa IP a cualquier nombre que
 * la lleve dentro, sin registrar nada.
 */
@Serializable
data class IpNameService(
    val id: String,
    val name: String,
    /** El nombre con `{ip}` (149.22.82.28) o `{ip-}` (149-22-82-28) donde va la IP. */
    val template: String,
    val enabled: Boolean = true,
    /** De fábrica: no se puede borrar, solo desactivar. */
    val builtIn: Boolean = false,
) {
    /** El nombre que resuelve a [ip]. */
    fun nameFor(ip: String): String = template.replace(DASHED, ip.replace('.', '-')).replace(PLAIN, ip)

    companion object {
        const val PLAIN = "{ip}"
        const val DASHED = "{ip-}"

        val SSLIP = IpNameService(id = "sslip", name = "sslip.io", template = "{ip-}.sslip.io", builtIn = true)
        val NIP = IpNameService(id = "nip", name = "nip.io", template = "{ip}.nip.io", builtIn = true)

        /** Los de fábrica, en su orden. */
        val BUILT_IN = listOf(SSLIP, NIP)
    }
}

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
    /**
     * Los nombres de la red local (los de sus dominios de búsqueda, como `uci.cu`) se resuelven con el DNS de la red y,
     * si su dirección es interna, van directos, sin proxy ni Otro País: el proxy institucional no suele abrirlos.
     */
    val intranetDirect: Boolean = true,
    /** Pedir al proxy las IP públicas por un nombre de [nameServices], en vez de por la IP tal cual. */
    val nameByIp: Boolean = true,
    /** Si ningún nombre sirve, probar por último la IP tal cual. */
    val tryRawIp: Boolean = true,
    /** En el orden en que se prueban; los desactivados se saltan. */
    val nameServices: List<IpNameService> = IpNameService.BUILT_IN,
    /** La cuota de la UCI: avisos, consulta y pausa. */
    val quota: QuotaSettings = QuotaSettings(),
)

/** Cómo se sigue la cuota de navegación de la UCI. */
@Serializable
data class QuotaSettings(
    /** Los porcentajes de la cuota a los que se avisa. Cada uno avisa una vez, hasta que el consumo vuelve a bajar. */
    val alerts: List<Int> = ALERT_THRESHOLDS,
    /** Cada cuántos minutos se consulta mientras el túnel está conectado. */
    val checkMinutes: Int = 5,
    /** Desconectar el túnel al llegar al [PAUSE_THRESHOLD] % de la cuota, para no agotarla sin querer. */
    val pauseAtLimit: Boolean = false,
    /**
     * Verificar el certificado del servicio. Apagado, como en UCIProxy: el de `cuotas.uci.cu` lo firma una autoridad de
     * la UCI («ROOT AC UCI», bajo la raíz del Estado) que ningún sistema trae, y con la verificación la consulta falla.
     */
    val verifyCertificate: Boolean = false,
    /** Otra dirección para el servicio, solo en modo desarrollo (para el simulador `tools/test_cuotas.py`). */
    val serviceUrl: String? = null,
) {
    companion object {
        val ALERT_THRESHOLDS = listOf(50, 75, 90)
        val CHECK_INTERVALS = listOf(5, 15, 30)
        const val PAUSE_THRESHOLD = 90
    }
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setAnswerConnectivityChecks(enabled: Boolean)

    fun setIntranetDirect(enabled: Boolean)

    fun setNameByIp(enabled: Boolean)

    fun setTryRawIp(enabled: Boolean)

    fun setNameServiceEnabled(id: String, enabled: Boolean)

    /** Sube ([delta] negativo) o baja ([delta] positivo) un servicio en la lista. No hace nada si ya está en el extremo. */
    fun moveNameService(id: String, delta: Int)

    /** Añade un servicio propio al final de la lista. Quien llama lo ha validado (ver [IpNameServiceValidator]). */
    fun addNameService(service: IpNameService)

    /** Quita un servicio propio. Los de fábrica no se pueden quitar, solo desactivar. */
    fun removeNameService(id: String)

    /** Deja los servicios como vienen de fábrica y activos, y borra los propios. */
    fun resetNameServices()

    /** Cambia los ajustes de la cuota a partir de los actuales. */
    fun updateQuota(change: (QuotaSettings) -> QuotaSettings)
}

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
