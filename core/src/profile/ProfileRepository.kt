package org.uci.proxy.core.profile

import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.VpnSecrets

interface ProfileRepository {
    /** Perfiles guardados, en el orden en que se crearon. */
    val profiles: StateFlow<List<ProxyProfile>>

    /**
     * Crea el perfil o reemplaza el que tenga el mismo id. Una contraseña vacía borra la guardada.
     *
     * @param vpn los secretos de la VPN si el perfil tiene una ([ProxyProfile.vpn] distinto de null); si no, se borran
     *   los que hubiera guardados.
     */
    suspend fun save(profile: ProxyProfile, password: String, vpn: VpnSecrets? = null)

    suspend fun delete(id: String)

    /** Contraseña del perfil, o cadena vacía si no tiene. */
    suspend fun password(id: String): String

    /** El `.ovpn` y la contraseña de la VPN del perfil, o null si no tiene VPN o no se pueden leer. */
    suspend fun vpnSecrets(id: String): VpnSecrets?
}
