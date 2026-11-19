package org.uci.proxy.core.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore

/**
 * Guarda la lista de perfiles como JSON en el [KeyValueStore] y cada contraseña en el [SecretStore].
 */
class StoredProfileRepository(
    private val keyValueStore: KeyValueStore,
    private val secretStore: SecretStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : ProfileRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val state = MutableStateFlow(load())

    override val profiles: StateFlow<List<ProxyProfile>> = state.asStateFlow()

    override suspend fun save(profile: ProxyProfile, password: String, exitSecrets: CountryExitSecrets?) = write {
        // Primero los secretos: si el almacén seguro falla, la lista no queda apuntando a un perfil a medias.
        if (password.isEmpty()) secretStore.remove(passwordKey(profile.id)) else secretStore.put(passwordKey(profile.id), password)
        // Solo quedan los secretos del motor actual: al cambiar de motor, los del anterior se borran.
        removeExitSecrets(profile.id)
        when (val secrets = exitSecrets.takeIf { profile.countryExit != null }) {
            is CountryExitSecrets.OpenVpn -> {
                secretStore.put(vpnFileKey(profile.id), secrets.ovpn)
                if (secrets.password.isNotEmpty()) secretStore.put(vpnPasswordKey(profile.id), secrets.password)
            }
            is CountryExitSecrets.Tailscale -> secretStore.put(tailscaleKey(profile.id), secrets.authKey)
            null -> Unit
        }

        val current = state.value
        val updated = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        persist(updated)
    }

    override suspend fun delete(id: String) = write {
        persist(state.value.filterNot { it.id == id })
        secretStore.remove(passwordKey(id))
        removeExitSecrets(id)
    }

    override suspend fun password(id: String): String = withContext(dispatchers.background) {
        secretStore.get(passwordKey(id)).orEmpty()
    }

    override suspend fun exitSecrets(id: String): CountryExitSecrets? = withContext(dispatchers.background) {
        when (state.value.firstOrNull { it.id == id }?.countryExit) {
            is CountryExit.OpenVpn -> {
                val ovpn = secretStore.get(vpnFileKey(id)) ?: return@withContext null
                CountryExitSecrets.OpenVpn(ovpn, secretStore.get(vpnPasswordKey(id)).orEmpty())
            }
            // La clave de Tailscale puede faltar: con la identidad del nodo ya guardada no hace falta para reconectar.
            is CountryExit.Tailscale -> CountryExitSecrets.Tailscale(secretStore.get(tailscaleKey(id)).orEmpty())
            null -> null
        }
    }

    private fun removeExitSecrets(id: String) {
        secretStore.remove(vpnFileKey(id))
        secretStore.remove(vpnPasswordKey(id))
        secretStore.remove(tailscaleKey(id))
    }

    private suspend fun write(block: () -> Unit) = withContext(dispatchers.background) {
        writeLock.withLock { block() }
    }

    private fun persist(profiles: List<ProxyProfile>) {
        keyValueStore.putString(PROFILES_KEY, json.encodeToString(profiles))
        state.value = profiles
    }

    private fun load(): List<ProxyProfile> {
        val stored = keyValueStore.getString(PROFILES_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<ProxyProfile>>(stored)
        } catch (e: SerializationException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudieron leer los perfiles guardados: ${e.message}")
            emptyList()
        }
    }

    private fun passwordKey(id: String) = "profile.$id.password"

    private fun vpnFileKey(id: String) = "profile.$id.vpn-file"

    private fun vpnPasswordKey(id: String) = "profile.$id.vpn-password"

    private fun tailscaleKey(id: String) = "profile.$id.tailscale-authkey"

    private companion object {
        const val TAG = "ProfileRepository"
        const val PROFILES_KEY = "profiles"
    }
}
