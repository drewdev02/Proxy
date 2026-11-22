package org.uci.proxy.connection

import kotlin.time.Duration
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.RuleKind

/** «El DNS de Otro País no resolvió a.com, b.com y 3 más: …», con los primeros nombres y cuántos quedan. */
internal fun viaProxyMessage(hosts: List<String>, shown: Int = 3): String {
    val names = hosts.takeLast(shown).asReversed().joinToString(", ")
    val rest = hosts.size - minOf(hosts.size, shown)
    val more = if (rest > 0) " y $rest más" else ""
    return "El DNS de Otro País no resolvió $names$more: sus conexiones salen por el proxy, no por Otro País."
}

/** `usuario@host:puerto`, o `host:puerto` si el perfil no tiene usuario. */
internal fun ProxyProfile.address(): String =
    if (username.isEmpty()) "$host:$port" else "$username@$host:$port"

/** Un tiempo como `02:45:07` (horas sin límite: un túnel puede llevar días abierto). */
internal fun formatElapsed(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    fun two(value: Long) = value.toString().padStart(2, '0')
    return "${two(total / 3600)}:${two(total % 3600 / 60)}:${two(total % 60)}"
}

/** «2 sitios, 1 app» a partir de cuántas reglas hay de cada tipo. */
internal fun Map<RuleKind, Int>.describe(): String = RuleKind.entries.mapNotNull { kind ->
    val count = this[kind] ?: return@mapNotNull null
    val (one, many) = when (kind) {
        RuleKind.DOMAIN -> "sitio" to "sitios"
        RuleKind.IP -> "red" to "redes"
        RuleKind.APP -> "app" to "apps"
    }
    "$count ${if (count == 1) one else many}"
}.joinToString(", ")
