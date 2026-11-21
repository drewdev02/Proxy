package org.uci.proxy.core.dns

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.DnsTransport
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.routing.RuleSet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Las direcciones de la red local: las que no salen a Internet y el túnel nunca captura. Una conexión hacia ellas va por
 * la red real, sin proxy.
 */
val INTRANET_RANGES: List<Ipv4Route> = listOf("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16").map(Ipv4Route::parse)

fun isIntranetAddress(address: Int): Boolean = INTRANET_RANGES.any { it.contains(address) }

/**
 * Qué nombres se resuelven con el DNS de la red local en vez de con el del túnel.
 *
 * - Con una regla de dominio «Directo», sí; con «Otro País» o «Bloquear», no.
 * - Con una regla «Proxy» o sin regla, sí los de los dominios de búsqueda de la red (`uci.cu` y sus subdominios), si el
 *   ajuste [intranetDirect] está activo: el proxy institucional no abre su propia intranet, así que para una dirección
 *   interna «Proxy» solo puede significar «sin Otro País». Si la dirección es pública, sigue yendo por el proxy.
 */
class LocalDnsPolicy(
    searchDomains: List<String>,
    private val intranetDirect: Boolean,
    private val rules: RuleSet? = null,
) {
    private val domains = searchDomains.map { it.lowercase().trim('.') }.filter { it.isNotEmpty() }.distinct()

    fun claims(name: String): Boolean {
        val host = name.lowercase().trimEnd('.')
        return when (rules?.domainExitFor(host)) {
            RouteExit.DIRECT -> true
            // Quien manda algo a Otro País o lo bloquea no quiere que salga por la red local.
            RouteExit.VPN, RouteExit.BLOCK -> false
            // «Proxy» (o ninguna regla): una dirección interna no la abre el proxy, así que si lo es, va directa.
            RouteExit.PROXY, null -> intranetDirect && domains.any { host == it || host.endsWith(".$it") }
        }
    }

    companion object {
        /**
         * El descubrimiento automático de proxy (WPAD). Resuelto de verdad, el navegador bajaría la configuración de la
         * red y hablaría con el proxy por su cuenta, saltándose el túnel; y si no, lo reintenta sin parar.
         */
        fun isWpad(name: String): Boolean {
            val host = name.lowercase().trimEnd('.')
            return host == WPAD || host.startsWith("$WPAD.")
        }

        private const val WPAD = "wpad"
    }
}

/**
 * Resuelve nombres con los servidores DNS de la red local y da su dirección solo si es interna: las públicas salen por
 * el proxy como siempre. Recuerda las respuestas un rato para no preguntar en cada conexión.
 *
 * Solo usa servidores con dirección interna: no entran en el túnel, así que la consulta sale por la red real.
 *
 * @param reserved rangos del propio túnel: una dirección dentro de ellos no se puede alcanzar directamente.
 */
class LocalDnsResolver(
    servers: List<String>,
    private val transport: DnsTransport,
    private val reserved: List<Ipv4Route>,
    private val logger: Logger,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** Los servidores utilizables, en su orden. */
    val servers: List<String> = servers.filter { server -> parseIpv4(server)?.let(::isIntranetAddress) == true }.distinct()

    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Cached>()
    private val inflight = HashMap<String, CompletableDeferred<Int?>>()
    private var nextId = 1

    private class Cached(val address: Int?, val expires: TimeMark)

    /** Lo que se sabe ya de un nombre, sin preguntar a nadie. */
    class Known(val address: Int?)

    /** Lo recordado de [name], o null si hay que preguntar (con [resolve]). */
    suspend fun known(name: String): Known? {
        val host = name.lowercase().trimEnd('.')
        return lock.withLock {
            val cached = cache[host] ?: return@withLock null
            if (cached.expires.hasNotPassedNow()) Known(cached.address) else null.also { cache.remove(host) }
        }
    }

    /**
     * La dirección interna de [name], o null si no la tiene (no existe, es pública, nadie contestó). Si ya se está
     * preguntando por ese nombre, espera a esa misma respuesta.
     */
    suspend fun resolve(name: String): Int? {
        val host = name.lowercase().trimEnd('.')
        known(host)?.let { return it.address }
        if (servers.isEmpty()) {
            remember(host, null, MISSING_TTL)
            return null
        }
        val (pending, owner) = lock.withLock {
            inflight[host]?.let { it to false } ?: CompletableDeferred<Int?>().also { inflight[host] = it }.let { it to true }
        }
        if (!owner) return pending.await()
        var address: Int? = null
        try {
            address = ask(host)
        } finally {
            // También si se cancela: quien espera recibe «sin dirección» en vez de quedarse colgado.
            withContext(NonCancellable) { lock.withLock { inflight.remove(host) } }
            pending.complete(address)
        }
        return address
    }

    private suspend fun ask(host: String): Int? {
        val id = lock.withLock { nextId.also { nextId = if (it == 0xFFFF) 1 else it + 1 } }
        val query = buildDnsQuery(host, id)
        for (server in servers) {
            val reply = transport.exchange(server, query, timeoutMs) ?: continue
            val addresses = dnsAnswerAddresses(reply)
            val address = addresses.firstOrNull { isIntranetAddress(it) && reserved.none { route -> route.contains(it) } }
            when {
                address != null -> logger.log(LogLevel.DEBUG, TAG, "Red local: $host → ${formatIpv4(address)}, directo")
                addresses.any { candidate -> reserved.any { it.contains(candidate) } } ->
                    logger.log(LogLevel.WARN, TAG, "Red local: $host está en la subred del túnel; sale por el proxy")
                addresses.isNotEmpty() -> logger.log(LogLevel.DEBUG, TAG, "Red local: $host no es interna; sale como siempre")
            }
            remember(host, address, if (address != null) FOUND_TTL else MISSING_TTL)
            return address
        }
        logger.log(LogLevel.DEBUG, TAG, "Red local: el DNS de la red no contestó por $host")
        remember(host, null, MISSING_TTL)
        return null
    }

    private suspend fun remember(host: String, address: Int?, ttl: Duration) = lock.withLock {
        cache.remove(host)
        if (cache.size >= MAX_CACHED) cache.keys.firstOrNull()?.let(cache::remove)
        cache[host] = Cached(address, timeSource.markNow() + ttl)
    }

    companion object {
        /** El TTL con que se contesta a las apps: por debajo de lo que se recuerda aquí. */
        const val ANSWER_TTL_SECONDS = 60

        private const val TAG = "Dns"
        private const val TIMEOUT_MS = 1_500L
        private const val MAX_CACHED = 1024
        private val FOUND_TTL = 120.seconds
        private val MISSING_TTL = 30.seconds
    }
}
