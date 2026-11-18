package org.uci.proxy.fakes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.model.VpnSecrets
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.proxy.ProxyTester

class FakeProfileRepository(initial: List<ProxyProfile> = emptyList()) : ProfileRepository {
    private val state = MutableStateFlow(initial)
    val passwords = mutableMapOf<String, String>()
    var failWrites = false

    override val profiles: StateFlow<List<ProxyProfile>> = state

    val vpnSecrets = mutableMapOf<String, VpnSecrets>()

    override suspend fun save(profile: ProxyProfile, password: String, vpn: VpnSecrets?) {
        check(!failWrites) { "almacén no disponible" }
        state.value = state.value.filterNot { it.id == profile.id } + profile
        passwords[profile.id] = password
        if (profile.vpn != null && vpn != null) vpnSecrets[profile.id] = vpn else vpnSecrets.remove(profile.id)
    }

    override suspend fun delete(id: String) {
        check(!failWrites) { "almacén no disponible" }
        state.value = state.value.filterNot { it.id == id }
        passwords.remove(id)
        vpnSecrets.remove(id)
    }

    override suspend fun password(id: String): String = passwords[id].orEmpty()

    override suspend fun vpnSecrets(id: String): VpnSecrets? = vpnSecrets[id]
}

/** Devuelve [result] y recuerda con qué se le llamó. Con [gate] la prueba queda en curso hasta completarlo. */
class FakeProxyTester(
    var result: ProxyTestResult = ProxyTestResult.Success,
    private val gate: CompletableDeferred<Unit>? = null,
) : ProxyTester {
    val calls = mutableListOf<Pair<ProxyProfile, String>>()

    override suspend fun test(profile: ProxyProfile, password: String): ProxyTestResult {
        calls += profile to password
        gate?.await()
        return result
    }
}

class FakeLocalNetworkPermission(var granted: Boolean = true) : LocalNetworkPermission {
    var requests = 0

    override suspend fun request(): Boolean {
        requests++
        return granted
    }
}

/** Túnel simulado: conectar pasa directamente a conectado. */
class FakeTunnel(override val isAvailable: Boolean = true) : TunnelControl, TunnelMonitor {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState
    val connectRequests = mutableListOf<String>()

    override suspend fun connect(profileId: String) {
        connectRequests += profileId
        mutableState.value = TunnelState.Connected(profileId)
    }

    override suspend fun disconnect() {
        mutableState.value = TunnelState.Disconnected
    }
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
