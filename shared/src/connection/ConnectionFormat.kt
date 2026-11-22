package org.uci.proxy.connection

import kotlin.time.Duration
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.resources.*

/** Los nombres que no resolvió el DNS de Otro País, para el aviso: los últimos [ViaProxyHosts.names] y cuántos más hay. */
internal data class ViaProxyHosts(val names: List<String>, val rest: Int)

/** Los [shown] nombres más recientes, del último al primero, y cuántos quedan sin nombrar. */
internal fun viaProxyHosts(hosts: List<String>, shown: Int = 3): ViaProxyHosts =
    ViaProxyHosts(hosts.takeLast(shown).asReversed(), hosts.size - minOf(hosts.size, shown))

/** `usuario@host:puerto`, o `host:puerto` si el perfil no tiene usuario. */
internal fun ProxyProfile.address(): String =
    if (username.isEmpty()) "$host:$port" else "$username@$host:$port"

/** Un tiempo como `02:45:07` (horas sin límite: un túnel puede llevar días abierto). */
internal fun formatElapsed(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    fun two(value: Long) = value.toString().padStart(2, '0')
    return "${two(total / 3600)}:${two(total % 3600 / 60)}:${two(total % 60)}"
}

/** Cuántas reglas hay de cada tipo, siempre en el mismo orden (sitios, redes, apps) y sin los tipos que no tienen. */
internal fun Map<RuleKind, Int>.inOrder(): List<Pair<RuleKind, Int>> =
    RuleKind.entries.mapNotNull { kind -> this[kind]?.let { kind to it } }
