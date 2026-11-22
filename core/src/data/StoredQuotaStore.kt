package org.uci.proxy.core.data

import org.uci.proxy.core.model.UciQuota
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.QuotaStore

/** [QuotaStore] sobre el almacén de claves, con una clave por cuenta y por dato. */
class StoredQuotaStore(private val keyValueStore: KeyValueStore) : QuotaStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun last(account: String): UciQuota? = keyValueStore.getString(key("last", account))?.let { stored ->
        try {
            json.decodeFromString<UciQuota>(stored)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun saveLast(account: String, quota: UciQuota) = keyValueStore.putString(key("last", account), json.encodeToString(quota))

    override fun alerted(account: String): Set<Int> =
        keyValueStore.getString(key("alerted", account)).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()

    override fun saveAlerted(account: String, thresholds: Set<Int>) =
        keyValueStore.putString(key("alerted", account), thresholds.sorted().joinToString(","))

    override fun overLimitAllowed(account: String): Boolean = keyValueStore.getString(key("override", account)) == "true"

    override fun setOverLimitAllowed(account: String, allowed: Boolean) = keyValueStore.putString(key("override", account), allowed.toString())

    private fun key(kind: String, account: String) = "quota.$kind.${account.lowercase()}"
}
