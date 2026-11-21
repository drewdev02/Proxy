package org.uci.proxy.core.routing

import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.parseIpv4

/**
 * Convierte lo que escribe la persona en una regla, o dice por qué no sirve. Acepta un dominio (con `*` como comodín y sin
 * `http://`, rutas ni puertos, que se quitan), una IP o un rango CIDR.
 *
 * @return la regla, o null si [text] no es ni un dominio ni una IP válidos.
 */
fun parseRule(text: String, exit: RouteExit): RouteRule? {
    val trimmed = text.trim().lowercase().substringAfter("://")
    if (isCidr(trimmed)) {
        val route = parseRoute(trimmed) ?: return null
        return RouteRule(RuleKind.IP, route.toString().removeSuffix("/32"), exit)
    }
    // Lo que sigue al nombre (la ruta de una URL, el puerto) no cuenta.
    val host = trimmed.substringBefore('/').substringBefore(':')
    if (parseIpv4(host) != null) return RouteRule(RuleKind.IP, host, exit)
    val domain = host.trim('.')
    if (domain.isEmpty() || domain.length > MAX_DOMAIN || !DOMAIN_PATTERN.matches(domain) || domain.all { it == '*' || it == '.' }) return null
    return RouteRule(RuleKind.DOMAIN, domain, exit)
}

private fun isCidr(text: String): Boolean = '/' in text && parseIpv4(text.substringBefore('/')) != null

/** Una IP sola es un /32. El rango se normaliza a su dirección de red (10.1.2.3/8 → 10.0.0.0/8). */
private fun parseRoute(text: String): Ipv4Route? {
    val address = parseIpv4(text.substringBefore('/')) ?: return null
    val prefix = if ('/' in text) text.substringAfter('/').toIntOrNull()?.takeIf { it in 0..32 } ?: return null else 32
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    return Ipv4Route(address and mask, prefix)
}

private const val MAX_DOMAIN = 253
private val DOMAIN_PATTERN = Regex("[a-z0-9*]([a-z0-9*-]*[a-z0-9*])?(\\.[a-z0-9*]([a-z0-9*-]*[a-z0-9*])?)*")

/**
 * Las reglas de un perfil listas para consultar en cada conexión. Se recorren por orden y gana la primera que coincide.
 *
 * Las reglas de app solo coinciden donde la plataforma dice qué app abrió cada conexión (Android).
 */
class RuleSet(private val routing: RoutingRules) {
    private sealed interface Matcher {
        val exit: RouteExit
    }

    private class DomainMatcher(pattern: String, override val exit: RouteExit) : Matcher {
        // Sin comodines, el dominio vale también para sus subdominios: «netflix.com» cubre «www.netflix.com».
        private val regex = if ('*' in pattern) {
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
        } else {
            Regex("(?:.*\\.)?" + Regex.escape(pattern))
        }

        fun matches(host: String) = regex.matches(host)
    }

    private class IpMatcher(val route: Ipv4Route, override val exit: RouteExit) : Matcher

    private class AppMatcher(val packageName: String, override val exit: RouteExit) : Matcher

    private val matchers: List<Matcher> = routing.rules.mapNotNull { rule ->
        when (rule.kind) {
            RuleKind.DOMAIN -> DomainMatcher(rule.value.lowercase(), rule.exit)
            RuleKind.IP -> parseRoute(rule.value)?.let { IpMatcher(it, rule.exit) }
            RuleKind.APP -> AppMatcher(rule.value, rule.exit)
        }
    }

    val defaultExit: RouteExit get() = routing.defaultExit

    /**
     * Rangos que no deben entrar en el túnel: los de las reglas de IP con salida directa. El sistema los manda por la red
     * real, así que ganan a cualquier otra regla.
     */
    val directRoutes: List<Ipv4Route> = matchers.filterIsInstance<IpMatcher>().filter { it.exit == RouteExit.DIRECT }.map { it.route }

    /** Apps que no deben entrar en el túnel: las de las reglas de app con salida directa. Como los rangos, ganan siempre. */
    val directApps: List<String> = matchers.filterIsInstance<AppMatcher>().filter { it.exit == RouteExit.DIRECT }.map { it.packageName }

    /** Si hace falta averiguar la app de cada conexión. */
    val hasAppRules: Boolean = matchers.any { it is AppMatcher && it.exit != RouteExit.DIRECT }

    /**
     * La salida de la primera regla de dominio que coincide con [host], o null si ninguna. Al resolver un nombre solo se
     * conoce el nombre: las reglas de IP y de app se aplican después, al conectar.
     */
    fun domainExitFor(host: String): RouteExit? {
        val name = host.lowercase().trimEnd('.')
        return matchers.firstOrNull { it is DomainMatcher && it.matches(name) }?.exit
    }

    /**
     * La salida de una conexión.
     *
     * @param host el nombre del destino si se conoce (por el DNS del túnel).
     * @param address la IP real del destino, o null si es una dirección inventada del DNS falso.
     * @param apps los paquetes de la app que abrió la conexión, si se saben.
     */
    fun exitFor(host: String?, address: Int?, apps: List<String> = emptyList()): RouteExit {
        val name = host?.lowercase()?.trimEnd('.')
        for (matcher in matchers) {
            val matches = when (matcher) {
                is DomainMatcher -> name != null && matcher.matches(name)
                is IpMatcher -> address != null && matcher.route.contains(address)
                is AppMatcher -> matcher.packageName in apps
            }
            if (matches) return matcher.exit
        }
        return routing.defaultExit
    }
}
