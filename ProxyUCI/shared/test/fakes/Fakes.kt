package org.uci.proxy.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.profile.ProfileRepository

class FakeProfileRepository(initial: List<ProxyProfile> = emptyList()) : ProfileRepository {
    private val state = MutableStateFlow(initial)
    val passwords = mutableMapOf<String, String>()
    var failWrites = false

    override val profiles: StateFlow<List<ProxyProfile>> = state

    override suspend fun save(profile: ProxyProfile, password: String) {
        check(!failWrites) { "almacén no disponible" }
        state.value = state.value.filterNot { it.id == profile.id } + profile
        passwords[profile.id] = password
    }

    override suspend fun delete(id: String) {
        check(!failWrites) { "almacén no disponible" }
        state.value = state.value.filterNot { it.id == id }
        passwords.remove(id)
    }

    override suspend fun password(id: String): String = passwords[id].orEmpty()
}

class RecordingLogger : Logger {
    val entries = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, tag: String, message: String) {
        entries += level to message
    }
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class InMemorySecretStore : SecretStore {
    private val secrets = mutableMapOf<String, String>()

    override fun put(key: String, value: String) {
        secrets[key] = value
    }

    override fun get(key: String): String? = secrets[key]

    override fun remove(key: String) {
        secrets.remove(key)
    }
}
