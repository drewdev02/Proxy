package org.uci.proxy.core.vpn

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.dnsAnswerAddresses
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.data.StoredProfileRepository
import org.uci.proxy.core.proxy.BasicAuthScheme
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.NtlmAuthScheme
import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Reglas de salida contra un servidor OpenVPN real, alcanzado por un proxy HTTP. No corre sola: se activa con
 *
 *   OVPN_FILE=/ruta/perfil.ovpn      OVPN_AUTH=/ruta/archivo (usuario y contraseña en dos líneas)
 *   SPLIT_PROXY=127.0.0.1:3128       SPLIT_PROXY_USER / SPLIT_PROXY_PASSWORD (opcionales)
 *
 * Un nombre con regla «Otro País» se resuelve por el DNS de la VPN y su conexión TCP recibe el SYN-ACK del servidor real
 * a través de la VPN; un nombre sin regla recibe una dirección inventada (va por el proxy).
 */
class SplitRoutingLiveTest {
    private val logger = object : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = println("[$level] $tag: $message")
    }

    @Test
    fun `a rule sends a name and its connection through a real VPN`() = runBlocking {
        val file = System.getenv("OVPN_FILE") ?: return@runBlocking
        val auth = File(System.getenv("OVPN_AUTH") ?: error("Falta OVPN_AUTH")).readLines()
        val (proxyHost, proxyPort) = (System.getenv("SPLIT_PROXY") ?: error("Falta SPLIT_PROXY")).split(':')

        val selector = SelectorManager(Dispatchers.IO)
        val random = SecureRandom()
        val randomBytes = RandomBytes { ByteArray(it).also(random::nextBytes) }
        val dispatchers = AppDispatchers(background = Dispatchers.IO)
        val tcp = KtorTcpConnector(selector, dispatchers)
        val connectors = listOf(HttpConnectConnector(tcp, listOf(NtlmAuthScheme(randomBytes, kotlin.time.Clock.System), BasicAuthScheme())))
        val client = OpenVpnClient(JcaOpenVpnCrypto(), SslEngineTlsSessionFactory(), randomBytes, logger, { System.currentTimeMillis() / 1000 })

        val stateStore = TunnelStateStore()
        val runner = TunnelRunner(
            profiles = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger),
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = randomBytes),
            engineFactory = TunnelEngineFactory(connectors, randomBytes, TimeSource.Monotonic, logger),
            hostResolver = { it },
            stateStore = stateStore,
            dispatchers = dispatchers,
            logger = logger,
            engines = CountryEngines(listOf(OpenVpnEngine(client, ProxyDialer(connectors, logger), randomBytes, logger))),
        )
        val profile = ProxyProfile(
            "p1", "Pruebas", proxyHost, proxyPort.toInt(), ProxyProtocol.HTTP, System.getenv("SPLIT_PROXY_USER").orEmpty(),
            CountryExit.OpenVpn("pais.ovpn", auth[0]),
            RoutingRules(enabled = true, rules = listOf(RouteRule(RuleKind.DOMAIN, "ifconfig.me", RouteExit.VPN))),
        )
        val platformTunnel = FakePlatformTunnel()
        val job = CoroutineScope(Dispatchers.IO).launch {
            runner.run(platformTunnel, profile, System.getenv("SPLIT_PROXY_PASSWORD").orEmpty(), vpn = CountryExitStart(profile.countryExit!!, CountryExitSecrets.OpenVpn(File(file).readText(), auth[1])))
        }
        try {
            val state = withTimeout(120_000) { stateStore.state.first { it is TunnelState.Connected || it is TunnelState.Failed } }
            println("ESTADO: $state")
            assertTrue(state is TunnelState.Connected && !state.vpnDown, "Otro País no conectó: $state")
            val config = assertNotNull(platformTunnel.config)
            val app = parseIpv4(config.address)!!
            val dns = parseIpv4(config.dnsServer)!!
            val tun = platformTunnel.tun

            suspend fun awaitWritten(predicate: (Ipv4Packet) -> Boolean): Ipv4Packet = withTimeout(30_000) {
                var seen = 0
                while (true) {
                    // La interfaz falsa no es segura entre hilos: si se escribe mientras se copia, se reintenta.
                    val snapshot = try {
                        tun.written.toList()
                    } catch (_: ConcurrentModificationException) {
                        delay(10)
                        continue
                    }
                    snapshot.drop(seen).mapNotNull(Ipv4Packet::parse).firstOrNull(predicate)?.let { return@withTimeout it }
                    seen = snapshot.size
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("")
            }

            fun dnsQuery(name: String, id: Int): ByteArray {
                val question = name.split('.').flatMap { listOf(it.length.toByte()) + it.encodeToByteArray().toList() } + 0.toByte()
                return byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) + question.toByteArray() + byteArrayOf(0, 1, 0, 1)
            }

            // Sin regla: dirección inventada al instante, para ir por el proxy.
            tun.inject(buildUdpPacket(app, dns, 41001, 53, dnsQuery("example.com", 0x1111)))
            val local = awaitWritten { it.source == dns && UdpDatagram.parse(it)?.destinationPort == 41001 }
            val fake = dnsAnswerAddresses(UdpDatagram.parse(local)!!.payload).single()
            println("example.com -> ${formatIpv4(fake)} (inventada)")
            assertTrue(formatIpv4(fake).startsWith("10."))

            // Con regla «Otro País»: la respuesta real del DNS de la VPN.
            tun.inject(buildUdpPacket(app, dns, 41002, 53, dnsQuery("ifconfig.me", 0x2222)))
            val answer = awaitWritten { it.source == dns && UdpDatagram.parse(it)?.destinationPort == 41002 }
            val payload = UdpDatagram.parse(answer)!!.payload
            val real = dnsAnswerAddresses(payload).first()
            println("ifconfig.me -> ${formatIpv4(real)} (por la VPN, pregunta: ${DnsResponder.questionName(payload)})")
            assertFalse(formatIpv4(real).startsWith("10."), "una IP real, no inventada")

            // Y su conexión TCP: el SYN-ACK del servidor real vuelve por la VPN a la dirección de la interfaz.
            tun.inject(buildTcpPacket(app, real, 40000, 80, sequence = 1000, acknowledgment = 0, flags = TcpFlags.SYN, window = 65535, mssOption = 1360))
            val synAck = awaitWritten { it.source == real && it.destination == app }
            val segment = assertNotNull(TcpSegment.parse(synAck))
            println("SYN-ACK de ${formatIpv4(synAck.source)}:${segment.sourcePort} -> ${formatIpv4(synAck.destination)}:${segment.destinationPort}")
            assertTrue(segment.has(TcpFlags.SYN) && segment.has(TcpFlags.ACK))
            assertTrue(segment.acknowledgment == 1001)
        } finally {
            job.cancelAndJoin()
            selector.close()
        }
    }
}
