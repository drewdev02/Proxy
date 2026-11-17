package org.uci.proxy.core.port

/**
 * Permiso para conectar con equipos de la red local, donde suele estar el proxy institucional.
 *
 * Android 17 lo exige en ejecución: sin él las conexiones a direcciones locales se quedan
 * colgadas hasta agotar el tiempo de espera. En las demás plataformas no hay nada que pedir
 * desde la app (iOS muestra su propio aviso la primera vez que se conecta).
 */
fun interface LocalNetworkPermission {
    /** Pide el permiso si hace falta. Devuelve true si la app puede usar la red local. */
    suspend fun request(): Boolean
}

/** Para las plataformas donde la app no tiene que pedir nada. */
class GrantedLocalNetworkPermission : LocalNetworkPermission {
    override suspend fun request(): Boolean = true
}
