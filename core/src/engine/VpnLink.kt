package org.uci.proxy.core.engine

import org.uci.proxy.core.engine.handler.ExitRouterHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.buildDnsQuery
import org.uci.proxy.core.dns.dnsAnswerAddresses
import org.uci.proxy.core.dns.dnsTransactionId
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.PROTOCOL_TCP
import org.uci.proxy.core.packet.PROTOCOL_UDP
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.rewriteAddresses
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.traffic.TrafficMeter
import org.uci.proxy.core.vpn.VpnSession
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * La VPN tal como la usa el motor con reglas de salida: puede no estar, caerse y volver.
 *
 * Las conexiones que van por ella salen de la interfaz con la dirección de la interfaz; aquí se les pone la que el
 * servidor VPN asignó, y a sus respuestas se les devuelve la de la interfaz. Como detrás de la interfaz solo hay un equipo,
 * basta con cambiar direcciones: los puertos ya son únicos.
 *
 * Una app con regla «Otro País» puede haber recibido una dirección inventada (el DNS no sabe qué app pregunta): entonces
 * su nombre se resuelve aquí por el DNS de la VPN, la conexión sale hacia la IP real y sus respuestas vuelven con la
 * inventada.
 *
 * La usan a la vez el bucle del motor (lo que sale) y el de la VPN (lo que entra), así que su estado va bajo un cerrojo.
 *
 * @param appAddress la dirección de la interfaz, desde la que salen las apps.
 * @param onViaProxy se llama con cada nombre que el DNS de la VPN no resolvió: sus conexiones salen por el proxy, y la
 *   interfaz debe poder decirlo en vez de mostrar que todo va por Otro País. Con [fallbackToProxy] apagado no se llama:
 *   esas conexiones se bloquean.
 * @param fallbackToProxy si lo que debía ir por la VPN y no puede (caída, o nombre sin dirección) sale por el proxy.
 */
class VpnLink(
    private val appAddress: Int,
    private val logger: Logger,
    private val onViaProxy: (String) -> Unit = {},
    /** Dónde se cuenta lo que llega por la VPN; lo que sale lo cuenta [ExitRouterHandler]. */
    private val meter: TrafficMeter? = null,
    val fallbackToProxy: Boolean = true,
    /** Para que caduque un nombre sin dirección cuando se bloquea; el del motor, para poder probarlo con tiempo virtual. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val lock = Mutex()

    /** Qué pasa con lo que no puede ir por la VPN, para el registro. */
    private val instead get() = if (fallbackToProxy) "sale por el proxy" else "se bloquea"
    private var session: VpnSession? = null
    private var vpnAddress = 0
    private var vpnDns = 0

    /** Nombre de cada IP real que dio el DNS de la VPN: así las reglas de dominio siguen valiendo al conectar. */
    private val hostByAddress = LinkedHashMap<Int, String>()

    /** Y al revés: la IP real de cada nombre, para las conexiones que llegaron con una dirección inventada. */
    private val addressByHost = LinkedHashMap<String, Int>()

    /** Consultas DNS de las apps enviadas por la VPN, por puerto e identificador, con el servidor al que preguntaron. */
    private val pendingDns = LinkedHashMap<Int, Int>()

    /** Resoluciones propias en curso, por identificador. */
    private val resolving = HashMap<Int, Pair<String, CompletableDeferred<Unit>>>()
    private val resolvingHosts = HashSet<String>()

    /**
     * Nombres que el DNS de la VPN no supo resolver, con cuándo se vuelve a preguntar. Si salen por el proxy, hasta que la
     * VPN se reconecte (null); si se bloquean, poco tiempo: un fallo pasajero del DNS no debe dejar un sitio sin abrir toda
     * la sesión.
     */
    private val unresolvable = HashMap<String, TimeMark?>()
    private var nextId = 1

    /** Conexiones cuyo destino se cambió de la dirección inventada a la real, para devolverles la inventada. */
    private data class Translated(val protocol: Int, val appPort: Int, val remote: Int, val remotePort: Int)

    private val inventedFor = LinkedHashMap<Translated, Int>()

    val isUp: Boolean get() = session != null

    suspend fun attach(newSession: VpnSession) = lock.withLock {
        session = newSession
        vpnAddress = parseIpv4(newSession.network.address) ?: 0
        vpnDns = newSession.network.dnsServers.firstNotNullOfOrNull(::parseIpv4) ?: FALLBACK_DNS
        pendingDns.clear()
        unresolvable.clear()
    }

    suspend fun detach() = lock.withLock { forget() }

    private fun forget() {
        session = null
        pendingDns.clear()
        resolving.values.forEach { it.second.complete(Unit) }
        resolving.clear()
        resolvingHosts.clear()
    }

    /**
     * Escribe en [current]; si la sesión murió, la suelta y la cierra (así el supervisor ve la caída y reintenta) para
     * que lo que iba por ella salga por el proxy en vez de tumbar el bucle del motor.
     */
    private suspend fun write(current: VpnSession, bytes: ByteArray): Boolean {
        try {
            current.writePacket(bytes)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogLevel.WARN, TAG, "No se pudo escribir en la VPN: ${e.message}. Su tráfico $instead")
            // Solo si sigue siendo la misma: el supervisor pudo haber puesto ya otra.
            val dropped = lock.withLock { (session === current).also { if (it) forget() } }
            if (dropped) current.close()
            return false
        }
    }

    /** Cierra la sesión en curso sin esperar al cerrojo, para despertar su lectura al detener el túnel. */
    fun closeSession() {
        session?.close()
    }

    suspend fun hostFor(address: Int): String? = lock.withLock { hostByAddress[address] }

    /** La IP real de [host] si ya se conoce por el DNS de la VPN. */
    suspend fun addressFor(host: String): Int? = lock.withLock { addressByHost[host.lowercase()] }

    /** Si el DNS de la VPN ya dijo que [host] no existe o no contestó. */
    suspend fun isUnresolvable(host: String): Boolean = lock.withLock {
        val name = host.lowercase()
        if (name !in unresolvable) return@withLock false
        val retryAt = unresolvable[name]
        if (retryAt != null && retryAt.hasPassedNow()) {
            unresolvable.remove(name)
            false
        } else {
            true
        }
    }

    /**
     * Manda [packet] por la VPN, hacia [destination] (la IP real, si la app conectó a una inventada).
     *
     * @return false si la VPN no está, y entonces la conexión debe ir por otro lado.
     */
    suspend fun send(packet: Ipv4Packet, destination: Int = packet.destination): Boolean {
        val (current, translated) = lock.withLock {
            val current = session ?: return false
            if (destination != packet.destination) {
                val ports = portsOf(packet)
                if (ports != null) {
                    remember(inventedFor, Translated(packet.protocol, ports.first, destination, ports.second), packet.destination, MAX_TRANSLATED)
                }
            }
            current to rewriteAddresses(packet, vpnAddress, destination)
        }
        return write(current, translated)
    }

    /**
     * Pregunta [packet] (una consulta DNS de una app) al DNS de la VPN, para que la app reciba la IP real y su conexión
     * pueda salir por la VPN. @return false si la VPN no está.
     */
    suspend fun sendDnsQuery(packet: Ipv4Packet, datagram: UdpDatagram, host: String): Boolean {
        val id = dnsTransactionId(datagram.payload) ?: return false
        val (current, translated) = lock.withLock {
            val current = session ?: return false
            remember(pendingDns, (datagram.sourcePort shl 16) or id, packet.destination, MAX_PENDING_DNS)
            current to rewriteAddresses(packet, vpnAddress, vpnDns)
        }
        logger.log(LogLevel.DEBUG, TAG, "DNS por Otro País: $host")
        return write(current, translated)
    }

    /**
     * Resuelve [host] por el DNS de la VPN y espera la respuesta, como mucho [timeoutMs]. Después, [addressFor] da la IP o
     * [isUnresolvable] dice que no la hay. Si ya se está resolviendo, no pregunta otra vez.
     */
    suspend fun resolve(host: String, timeoutMs: Long = RESOLVE_TIMEOUT_MS) {
        val name = host.lowercase()
        val (current, query, done) = lock.withLock {
            val current = session ?: return
            if (name in addressByHost || !resolvingHosts.add(name)) return
            val id = nextId
            nextId = if (nextId == 0xFFFF) 1 else nextId + 1
            val done = CompletableDeferred<Unit>()
            resolving[id] = name to done
            Triple(current, buildUdpPacket(vpnAddress, vpnDns, RESOLVER_PORT, DNS_PORT, buildDnsQuery(name, id)), done)
        }
        logger.log(LogLevel.DEBUG, TAG, "Resolviendo por Otro País: $name")
        if (!write(current, query)) {
            lock.withLock {
                resolvingHosts.remove(name)
                resolving.entries.removeAll { it.value.first == name }
            }
            return
        }
        val answered = withTimeoutOrNull(timeoutMs) { done.await() } != null
        val failed = lock.withLock {
            resolvingHosts.remove(name)
            resolving.entries.removeAll { it.value.first == name }
            val failed = !answered || name !in addressByHost
            if (failed) unresolvable[name] = if (fallbackToProxy) null else timeSource.markNow() + (if (answered) NO_ADDRESS_RETRY else NO_ANSWER_RETRY)
            failed
        }
        // A la vista en el registro: sin esto, la conexión sale por el proxy (o se bloquea) en silencio y parece que va por
        // Otro País.
        if (failed) {
            val why = if (answered) "no tiene dirección para" else "no contestó por"
            val conexiones = if (fallbackToProxy) "sus conexiones salen por el proxy" else "sus conexiones se bloquean"
            logger.log(LogLevel.WARN, TAG, "El DNS de Otro País $why $name: $conexiones")
            if (fallbackToProxy) onViaProxy(name)
        }
    }

    /**
     * Un paquete que llega de la VPN, ya con las direcciones que la app espera.
     *
     * @return null si no es para nosotros (o si era la respuesta a una resolución propia).
     */
    suspend fun inbound(bytes: ByteArray): ByteArray? {
        val packet = Ipv4Packet.parse(bytes) ?: return null
        var from: String? = null
        val reply = lock.withLock {
            if (session == null || packet.destination != vpnAddress) return@withLock null
            val datagram = if (packet.source == vpnDns) UdpDatagram.parse(packet)?.takeIf { it.sourcePort == DNS_PORT } else null
            if (datagram != null && datagram.destinationPort == RESOLVER_PORT) {
                val payload = datagram.payload
                val id = dnsTransactionId(payload)
                learn(payload)
                id?.let { resolving.remove(it) }?.second?.complete(Unit)
                return@withLock null
            }
            val askedServer = datagram?.let { reply ->
                val payload = reply.payload
                val id = dnsTransactionId(payload) ?: return@let null
                pendingDns.remove((reply.destinationPort shl 16) or id)?.also {
                    learn(payload)
                }
            }
            val invented = portsOf(packet)?.let { (remotePort, appPort) ->
                inventedFor[Translated(packet.protocol, appPort, packet.source, remotePort)]
            }
            from = hostByAddress[packet.source] ?: formatIpv4(packet.source)
            rewriteAddresses(packet, askedServer ?: invented ?: packet.source, appAddress)
        }
        if (reply != null) meter?.count(RouteExit.VPN, from, app = null, up = 0, down = packet.totalLength.toLong())
        return reply
    }

    /** Apunta el nombre de cada IP de la respuesta, y la primera IP de cada nombre. Se llama con el cerrojo tomado. */
    private fun learn(answer: ByteArray) {
        val name = DnsResponder.questionName(answer) ?: return
        val addresses = dnsAnswerAddresses(answer)
        addresses.forEach { remember(hostByAddress, it, name, MAX_HOSTS) }
        addresses.firstOrNull()?.let { remember(addressByHost, name, it, MAX_HOSTS) }
    }

    /** Puertos de origen y destino de un paquete TCP o UDP (ambos empiezan igual). */
    private fun portsOf(packet: Ipv4Packet): Pair<Int, Int>? {
        if ((packet.protocol != PROTOCOL_TCP && packet.protocol != PROTOCOL_UDP) || packet.payloadLength < 4) return null
        val offset = packet.headerLength
        val bytes = packet.bytes
        fun u16(at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        return u16(offset) to u16(offset + 2)
    }

    /** Guarda en un mapa acotado: lleno, se olvida lo más antiguo. */
    private fun <K, V> remember(map: LinkedHashMap<K, V>, key: K, value: V, max: Int) {
        map.remove(key)
        if (map.size >= max) map.keys.firstOrNull()?.let(map::remove)
        map[key] = value
    }

    private companion object {
        const val TAG = "Vpn"
        const val DNS_PORT = 53

        // Puerto propio para las resoluciones del motor: fuera del rango efímero que usan las apps.
        const val RESOLVER_PORT = 1053
        const val RESOLVE_TIMEOUT_MS = 5_000L

        // Con el bloqueo, cuándo se vuelve a preguntar por un nombre sin dirección o sin respuesta.
        val NO_ADDRESS_RETRY = 60.seconds
        val NO_ANSWER_RETRY = 10.seconds
        const val MAX_HOSTS = 8192
        const val MAX_PENDING_DNS = 1024
        const val MAX_TRANSLATED = 8192

        // 1.1.1.1, si el servidor no empuja un DNS: de todos modos va por la VPN.
        const val FALLBACK_DNS = (1 shl 24) or (1 shl 16) or (1 shl 8) or 1
    }
}
