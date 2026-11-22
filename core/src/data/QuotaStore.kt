package org.uci.proxy.core.data

import org.uci.proxy.core.model.UciQuota
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.uci.proxy.core.port.KeyValueStore

/**
 * Lo que se recuerda de la cuota de cada cuenta entre sesiones: la última consultada (para enseñarla al abrir, sin esperar
 * a la red), los avisos ya dados y si se pidió seguir conectado pasado el límite.
 */
class QuotaStore(private val keyValueStore: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun last(account: String): UciQuota? = keyValueStore.getString(key("last", account))?.let { stored ->
        try {
            json.decodeFromString<UciQuota>(stored)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun saveLast(account: String, quota: UciQuota) = keyValueStore.putString(key("last", account), json.encodeToString(quota))

    /** Los umbrales de los que ya se avisó y por encima de los que se sigue: no se repiten hasta que el consumo baje. */
    fun alerted(account: String): Set<Int> =
        keyValueStore.getString(key("alerted", account)).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()

    fun saveAlerted(account: String, thresholds: Set<Int>) =
        keyValueStore.putString(key("alerted", account), thresholds.sorted().joinToString(","))

    /** Si se pidió seguir conectado pasado el límite de la pausa («Conectar igual»), mientras el consumo no baje. */
    fun overLimitAllowed(account: String): Boolean = keyValueStore.getString(key("override", account)) == "true"

    fun setOverLimitAllowed(account: String, allowed: Boolean) = keyValueStore.putString(key("override", account), allowed.toString())

    private fun key(kind: String, account: String) = "quota.$kind.${account.lowercase()}"
}
