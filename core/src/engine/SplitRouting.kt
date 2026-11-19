package org.uci.proxy.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.dns.buildDnsQuery
import org.uci.proxy.core.dns.dnsAnswerAddresses
import org.uci.proxy.core.dns.dnsTransactionId
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.PROTOCOL_TCP
import org.uci.proxy.core.packet.PROTOCOL_UDP
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildIcmpPortUnreachable
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.rewriteAddresses
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.routing.RuleSet
import org.uci.proxy.core.vpn.VpnSession

/** Lo que el motor necesita para repartir las conexiones entre el proxy, la VPN y el bloqueo. */
class SplitRouting(val rules: RuleSet, val link: VpnLink)

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
 */
class VpnLink(private val appAddress: Int, private val logger: Logger) {
    private val lock = Mutex()
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

    /** Nombres que el DNS de la VPN no supo resolver: sus conexiones van por el proxy. */
    private val unresolvable = HashSet<String>()
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

    suspend fun detach() = lock.withLock {
        session = null
        pendingDns.clear()
        resolving.values.forEach { it.second.complete(Unit) }
        resolving.clear()
        resolvingHosts.clear()
    }

    /** Cierra la sesión en curso sin esperar al cerrojo, para despertar su lectura al detener el túnel. */
    fun closeSession() {
        session?.close()
    }

    suspend fun hostFor(address: Int): String? = lock.withLock { hostByAddress[address] }

    /** La IP real de [host] si ya se conoce por el DNS de la VPN. */
    suspend fun addressFor(host: String): Int? = lock.withLock { addressByHost[host.lowercase()] }

    /** Si el DNS de la VPN ya dijo que [host] no existe o no contestó. */
    suspend fun isUnresolvable(host: String): Boolean = lock.withLock { host.lowercase() in unresolvable }

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
        current.writePacket(translated)
        return true
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
        current.writePacket(translated)
        return true
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
        current.writePacket(query)
        val answered = withTimeoutOrNull(timeoutMs) { done.await() } != null
        lock.withLock {
            resolvingHosts.remove(name)
            resolving.entries.removeAll { it.value.first == name }
            if (!answered || name !in addressByHost) unresolvable += name
        }
    }

    /**
     * Un paquete que llega de la VPN, ya con las direcciones que la app espera.
     *
     * @return null si no es para nosotros (o si era la respuesta a una resolución propia).
     */
    suspend fun inbound(bytes: ByteArray): ByteArray? {
        val packet = Ipv4Packet.parse(bytes) ?: return null
        return lock.withLock {
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
            rewriteAddresses(packet, askedServer ?: invented ?: packet.source, appAddress)
        }
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
        const val MAX_HOSTS = 8192
        const val MAX_PENDING_DNS = 1024
        const val MAX_TRANSLATED = 8192

        // 1.1.1.1, si el servidor no empuja un DNS: de todos modos va por la VPN.
        const val FALLBACK_DNS = (1 shl 24) or (1 shl 16) or (1 shl 8) or 1
    }
}

/**
 * Con reglas de salida, las consultas DNS de nombres que salen por la VPN se preguntan al DNS de la VPN: la app recibe la
 * IP real y su conexión puede ir por la VPN tal cual. El resto se contesta con el DNS falso, como siempre.
 */
class SplitDnsHandler(
    private val routing: SplitRouting,
    private val fallback: PacketHandler,
    /** Los nombres que el túnel contesta él mismo (las comprobaciones de conectividad) nunca van a la VPN. */
    private val hasOverride: (String) -> Boolean,
) : PacketHandler {
    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val datagram = UdpDatagram.parse(packet) ?: return null
        if (datagram.destinationPort != DNS_PORT) return null
        val question = DnsResponder.parseQuestionOf(datagram.payload)
        if (question != null && question.type == DnsResponder.TYPE_A && !hasOverride(question.name) &&
            routing.rules.exitFor(question.name, address = null) == RouteExit.VPN &&
            routing.link.sendDnsQuery(packet, datagram, question.name)
        ) {
            return emptyList()
        }
        return fallback.handle(packet)
    }

    private companion object {
        const val DNS_PORT = 53
    }
}

/**
 * Decide la salida de cada conexión TCP o UDP según las reglas. Lo que va por la VPN o se bloquea se resuelve aquí; lo que
 * va por el proxy sigue a los manejadores de siempre.
 *
 * La decisión se toma al abrirse la conexión (el SYN, o el primer datagrama UDP), que es cuando se pregunta qué app la
 * abrió, y vale para el resto de sus paquetes. Solo lo usa el bucle del motor, así que su tabla no necesita cerrojo.
 *
 * @param scope donde se resuelven por la VPN los nombres de las apps con regla «Otro País» que recibieron una dirección
 *   inventada. Mientras tanto sus paquetes se descartan: la app reintenta el SYN y para entonces ya hay IP real.
 */
class ExitRouterHandler(
    private val routing: SplitRouting,
    private val fakeDns: FakeDnsTable,
    private val logger: Logger,
    private val owner: ConnectionOwner = ConnectionOwner.Unknown,
    private val scope: CoroutineScope? = null,
) : PacketHandler {
    private data class Flow(val protocol: Int, val sourcePort: Int, val destination: Int, val destinationPort: Int)

    /** La salida de cada conexión, con el nombre de destino que tenía al decidirse. */
    private val flows = LinkedHashMap<Flow, Pair<String?, RouteExit>>()

    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val segment = TcpSegment.parse(packet)
        val datagram = if (segment == null) UdpDatagram.parse(packet) ?: return null else null
        val sourcePort = segment?.sourcePort ?: datagram!!.sourcePort
        val destinationPort = segment?.destinationPort ?: datagram!!.destinationPort

        val destination = packet.destination
        val fake = fakeDns.contains(destination)
        val host = if (fake) fakeDns.hostFor(destination) ?: return null else routing.link.hostFor(destination)
        val flow = Flow(packet.protocol, sourcePort, destination, destinationPort)
        val opening = if (segment != null) segment.has(TcpFlags.SYN) && !segment.has(TcpFlags.ACK) else flow !in flows
        val exit = decide(flow, packet, host, fake, opening)
        val target = "${host ?: formatIpv4(destination)}:$destinationPort"

        return when (exit) {
            RouteExit.VPN -> sendThroughVpn(packet, host, fake, opening, target)
            RouteExit.BLOCK -> {
                if (opening) logger.log(LogLevel.DEBUG, TAG, "Bloqueado: $target")
                listOfNotNull(if (segment != null) reset(packet, segment) else buildIcmpPortUnreachable(packet))
            }
            // Directo solo existe para rangos de IP y apps, que ni entran en la interfaz; un dominio con esa salida va por el proxy.
            RouteExit.PROXY, RouteExit.DIRECT -> null
        }
    }

    /** @return lo que se devuelve a la app (nada), o null si la conexión debe seguir por el proxy. */
    private suspend fun sendThroughVpn(packet: Ipv4Packet, host: String?, fake: Boolean, opening: Boolean, target: String): List<ByteArray>? {
        val link = routing.link
        if (!link.isUp) return null
        if (!fake) {
            if (!link.send(packet)) return null
            if (opening) logger.log(LogLevel.DEBUG, TAG, "Otro País: $target")
            return emptyList()
        }
        // Dirección inventada: hace falta la IP real del nombre, que da el DNS de la VPN.
        val name = host ?: return null
        val real = link.addressFor(name)
        if (real != null) {
            if (!link.send(packet, destination = real)) return null
            if (opening) logger.log(LogLevel.DEBUG, TAG, "Otro País: $target (${formatIpv4(real)})")
            return emptyList()
        }
        if (link.isUnresolvable(name) || scope == null) return null
        scope.launch { link.resolve(name) }
        return emptyList()
    }

    private fun decide(flow: Flow, packet: Ipv4Packet, host: String?, fake: Boolean, opening: Boolean): RouteExit {
        // Un SYN nuevo se decide otra vez: el mismo puerto puede reutilizarlo otra app.
        if (!opening) flows[flow]?.let { (knownHost, exit) -> if (knownHost == host) return exit }
        val rules = routing.rules
        // Solo al abrirse: preguntar al sistema por cada paquete sería caro, y la app no cambia.
        val apps = if (opening && rules.hasAppRules) owner.appsFor(flow.protocol, packet.source, flow.sourcePort, flow.destination, flow.destinationPort) else emptyList()
        val exit = rules.exitFor(host, address = if (fake) null else flow.destination, apps = apps)
        if (apps.isNotEmpty() && exit != rules.exitFor(host, address = if (fake) null else flow.destination)) {
            logger.log(LogLevel.DEBUG, TAG, "App ${apps.first()}: ${exit.name.lowercase()} hacia ${host ?: formatIpv4(flow.destination)}")
        }
        flows.remove(flow)
        if (flows.size >= MAX_FLOWS) flows.keys.firstOrNull()?.let(flows::remove)
        flows[flow] = host to exit
        return exit
    }

    private fun reset(packet: Ipv4Packet, segment: TcpSegment): ByteArray? {
        if (segment.has(TcpFlags.RST)) return null
        val consumed = segment.payloadLength + if (segment.has(TcpFlags.SYN)) 1 else 0
        return buildTcpPacket(
            source = packet.destination,
            destination = packet.source,
            sourcePort = segment.destinationPort,
            destinationPort = segment.sourcePort,
            sequence = if (segment.has(TcpFlags.ACK)) segment.acknowledgment else 0,
            acknowledgment = segment.sequence + consumed,
            flags = TcpFlags.RST or TcpFlags.ACK,
            window = 0,
        )
    }

    private companion object {
        const val TAG = "Rutas"
        const val MAX_FLOWS = 16384
    }
}
