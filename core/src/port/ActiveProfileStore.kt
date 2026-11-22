package org.uci.proxy.core.port

import kotlinx.coroutines.flow.StateFlow

/**
 * El perfil que la persona eligió en la pantalla «Conexión», recordado entre sesiones para no tener que elegirlo
 * cada vez. Solo guarda el id: si ese perfil se borra, quien lo lee debe elegir otro (ver `resolveActiveProfileId`).
 */
interface ActiveProfileStore {
    val activeProfileId: StateFlow<String?>

    fun select(profileId: String)
}
