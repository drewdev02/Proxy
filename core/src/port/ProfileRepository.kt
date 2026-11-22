package org.uci.proxy.core.port

import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.CountryExitSecrets

interface ProfileRepository {
    /** Perfiles guardados, en el orden en que se crearon. */
    val profiles: StateFlow<List<ProxyProfile>>

    /**
     * Crea el perfil o reemplaza el que tenga el mismo id. Una contraseña vacía borra la guardada.
     *
     * @param exitSecrets los secretos del motor de Otro País si el perfil sale por él ([ProxyProfile.countryExit]
     *   distinto de null); si no, se borran los que hubiera guardados.
     */
    suspend fun save(profile: ProxyProfile, password: String, exitSecrets: CountryExitSecrets? = null)

    suspend fun delete(id: String)

    /** Contraseña del perfil, o cadena vacía si no tiene. */
    suspend fun password(id: String): String

    /** Los secretos del motor de Otro País del perfil, o null si no sale por Otro País o no se pueden leer. */
    suspend fun exitSecrets(id: String): CountryExitSecrets?
}
