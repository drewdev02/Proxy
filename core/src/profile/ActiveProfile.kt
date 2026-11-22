package org.uci.proxy.core.profile

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
