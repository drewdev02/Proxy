package org.uci.proxy.core.profile

import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.model.ProxyProfile

interface ProfileRepository {
    /** Perfiles guardados, en el orden en que se crearon. */
    val profiles: StateFlow<List<ProxyProfile>>

    /** Crea el perfil o reemplaza el que tenga el mismo id. Una contraseña vacía borra la guardada. */
    suspend fun save(profile: ProxyProfile, password: String)

    suspend fun delete(id: String)

    /** Contraseña del perfil, o cadena vacía si no tiene. */
    suspend fun password(id: String): String
}
