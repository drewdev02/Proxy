package org.uci.proxy.core.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.uci.proxy.core.port.KeyValueStore

/**
 * El perfil que la persona eligió en la pantalla «Conexión», recordado entre sesiones para no tener que elegirlo
 * cada vez. Solo guarda el id: si ese perfil se borra, quien lo lee debe elegir otro (ver [resolveActiveProfileId]).
 */
interface ActiveProfileStore {
    val activeProfileId: StateFlow<String?>

    fun select(profileId: String)
}

class StoredActiveProfileStore(private val keyValueStore: KeyValueStore) : ActiveProfileStore {
    private val state = MutableStateFlow(keyValueStore.getString(ACTIVE_PROFILE_KEY))

    override val activeProfileId: StateFlow<String?> = state.asStateFlow()

    override fun select(profileId: String) {
        keyValueStore.putString(ACTIVE_PROFILE_KEY, profileId)
        state.value = profileId
    }

    companion object {
        const val ACTIVE_PROFILE_KEY = "active_profile_id"
    }
}

/**
 * Qué perfil mostrar como activo:
 * 1. el que usa el túnel, si está conectando o conectado (manda lo que pasa de verdad);
 * 2. si no, el elegido, mientras siga existiendo;
 * 3. si no, el primero de la lista; null si no hay ninguno.
 */
fun resolveActiveProfileId(profileIds: List<String>, chosen: String?, inTunnel: String?): String? = when {
    inTunnel != null && inTunnel in profileIds -> inTunnel
    chosen != null && chosen in profileIds -> chosen
    else -> profileIds.firstOrNull()
}
