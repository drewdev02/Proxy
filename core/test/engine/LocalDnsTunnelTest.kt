package org.uci.proxy.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.dns.dnsQuery
import org.uci.proxy.core.fakes.FakeDnsTransport
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.fakes.fakeEngines
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnNetwork
import org.uci.proxy.core.vpn.VpnSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Una sesión VPN en memoria que solo apunta lo que se le envía. */
private class RecordingVpnSession : VpnSession {
    override val network = VpnNetwork(address = "10.98.0.3", prefixLength = 24, dnsServers = listOf("10.98.0.1"), mtu = 1500)
    val sent = mutableListOf<ByteArray>()
    private val fromServer = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun writePacket(packet: ByteArray) {
        sent += packet
    }

    override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) {
        for (packet in fromServer) onPacket(packet)
    }

    override fun close() {
        fromServer.close()
    }
}

/** Los sitios de la red local (los de sus dominios de búsqueda) se resuelven con su DNS y van directos. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDnsTunnelTest {
    private val logger = RecordingLogger()
    private val platformTunnel = FakePlatformTunnel()
    private val tun get() = platformTunnel.tun
    private val connector = FakeProxyConnector.connectingTo(FakeTunnelConnection())
    private val network = LocalDnsConfig(servers = listOf("10.0.0.3"), searchDomains = listOf("uci.cu"))
    private val transport = FakeDnsTransport(
        mapOf(
            "10.0.0.3" to mapOf(
                "drst.uci.cu" to listOf("10.0.0.13"),
                "www.uci.cu" to listOf("200.55.140.3"),
                "wiki.intranet.example" to listOf("10.20.0.7"),
                "publico.example" to listOf("190.92.1.1"),
            ),
        ),
    )
    private val session = RecordingVpnSession()
    private val start = CountryExitStart(CountryExit.OpenVpn("pais.ovpn", "vpnuser"), CountryExitSecrets.OpenVpn("client\n", "vpnpass"))
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

    private fun TestScope.connect(
        profile: ProxyProfile = this@LocalDnsTunnelTest.profile,
        settings: AppSettings = AppSettings(),
        config: LocalDnsConfig = network,
        vpn: CountryExitStart? = null,
    ) = backgroundScope.launch {
        val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher(testScheduler))
        val runner = TunnelRunner(
            profiles = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger),
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
            engineFactory = TunnelEngineFactory(listOf(connector), { ByteArray(it) { 1 } }, testScheduler.timeSource, logger),
            hostResolver = { "10.8.0.20" },
            stateStore = TunnelStateStore(),
            dispatchers = dispatchers,
            logger = logger,
            engines = fakeEngines { _, _ -> VpnConnectResult.Connected(session) },
            localDnsSource = LocalDnsSource { config },
            dnsTransport = transport,
        )
        runner.run(platformTunnel, profile, "x", settings, vpn)
    }.also { runCurrent() }

    private val app get() = parseIpv4(assertNotNull(platformTunnel.config).address)!!
    private val tunDns get() = parseIpv4(assertNotNull(platformTunnel.config).dnsServer)!!

    private fun TestScope.send(packet: ByteArray) {
        tun.inject(packet)
        runCurrent()
    }

    private fun query(name: String, type: Int = 1, port: Int = 41000) =
        buildUdpPacket(app, tunDns, sourcePort = port, destinationPort = 53, payload = dnsQuery(name, type = type, id = 0x4242))

    private fun TestScope.askDns(name: String, type: Int = 1, port: Int = 41000) = send(query(name, type, port))

    /** La respuesta DNS que recibió la app: su código y la dirección, si trae una. */
    private fun lastAnswer(): Pair<Int, String?> {
        val payload = assertNotNull(UdpDatagram.parse(assertNotNull(Ipv4Packet.parse(tun.written.last())))).payload
        val code = payload.readU16(2) and 0x000F
        val address = if (payload.readU16(6) > 0) formatIpv4(payload.readInt(payload.size - 4)) else null
        return code to address
    }

    private val fakeSubnet get() = assertNotNull(platformTunnel.config).routes.first()

    @Test
    fun `a name of the local network gets its real internal address`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("drst.uci.cu")

        assertEquals(0 to "10.0.0.13", lastAnswer())
        assertEquals(listOf("10.0.0.3" to "drst.uci.cu"), transport.asked)
        assertTrue(platformTunnel.config!!.routes.none { it.contains(parseIpv4("10.0.0.13")!!) }, "esa dirección no entra en el túnel")
    }

    @Test
    fun `a local name with a public address gets the fake one as always`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("www.uci.cu")

        val (_, address) = lastAnswer()
        assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(address))!!), "una dirección inventada: va por el proxy")
    }

    @Test
    fun `names outside the local network are not asked to its DNS`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("www.google.com")

        assertTrue(transport.asked.isEmpty())
        assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(lastAnswer().second))!!))
    }

    @Test
    fun `with the setting off the local network goes through the proxy`() = runTest(UnconfinedTestDispatcher()) {
        connect(settings = AppSettings(intranetDirect = false))

        askDns("drst.uci.cu")

        assertTrue(transport.asked.isEmpty())
        assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(lastAnswer().second))!!))
    }

    @Test
    fun `other questions about a local name get an empty answer`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("drst.uci.cu", type = 28)

        assertEquals(0 to null, lastAnswer())
    }

    @Test
    fun `WPAD does not exist while the tunnel is up`() = runTest(UnconfinedTestDispatcher()) {
        connect(settings = AppSettings(intranetDirect = false))

        askDns("wpad.uci.cu")
        assertEquals(3 to null, lastAnswer(), "NXDOMAIN")
        askDns("wpad")
        assertEquals(3 to null, lastAnswer())
        assertTrue(transport.asked.isEmpty())
    }

    @Test
    fun `a public network DNS is never used`() = runTest(UnconfinedTestDispatcher()) {
        connect(config = LocalDnsConfig(servers = listOf("1.1.1.1"), searchDomains = listOf("uci.cu")))

        askDns("drst.uci.cu")

        assertTrue(transport.asked.isEmpty())
        assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(lastAnswer().second))!!))
    }

    private val rulesProfile = profile.copy(
        countryExit = CountryExit.OpenVpn("pais.ovpn", "vpnuser"),
        routing = RoutingRules(
            enabled = true,
            defaultExit = RouteExit.VPN,
            rules = listOf(
                RouteRule(RuleKind.DOMAIN, "intranet.example", RouteExit.DIRECT),
                RouteRule(RuleKind.DOMAIN, "publico.example", RouteExit.DIRECT),
                RouteRule(RuleKind.DOMAIN, "correo.uci.cu", RouteExit.VPN),
                RouteRule(RuleKind.DOMAIN, "*.uci.cu", RouteExit.PROXY),
            ),
        ),
    )

    @Test
    fun `with rules and Otro País by default the local network still goes direct`() = runTest(UnconfinedTestDispatcher()) {
        connect(rulesProfile, vpn = start)

        askDns("drst.uci.cu")

        assertEquals(0 to "10.0.0.13", lastAnswer())
        assertTrue(session.sent.isEmpty(), "no se pregunta al DNS de la VPN")
    }

    @Test
    fun `a direct domain rule uses the local DNS even outside its domains`() = runTest(UnconfinedTestDispatcher()) {
        connect(rulesProfile, settings = AppSettings(intranetDirect = false), vpn = start)

        askDns("wiki.intranet.example")

        assertEquals(0 to "10.20.0.7", lastAnswer())
    }

    @Test
    fun `a direct domain with a public address goes through the proxy`() = runTest(UnconfinedTestDispatcher()) {
        connect(rulesProfile, vpn = start)
        askDns("publico.example")
        val fake = parseIpv4(assertNotNull(lastAnswer().second))!!
        assertTrue(fakeSubnet.contains(fake))

        send(buildTcpPacket(app, fake, 50000, 443, sequence = 100, acknowledgment = 0, flags = TcpFlags.SYN, window = 65535))

        assertTrue(session.sent.isEmpty(), "no va por Otro País")
        val reply = assertNotNull(Ipv4Packet.parse(tun.written.last()))
        val segment = assertNotNull(TcpSegment.parse(reply))
        assertEquals(fake, reply.source)
        assertTrue(segment.has(TcpFlags.SYN) && segment.has(TcpFlags.ACK), "la pila del proxy la atiende")
    }

    @Test
    fun `a name sent to Otro País by a rule is not asked to the local DNS`() = runTest(UnconfinedTestDispatcher()) {
        connect(rulesProfile, vpn = start)

        askDns("correo.uci.cu")

        assertTrue(transport.asked.isEmpty())
        assertEquals(1, session.sent.size, "se pregunta al DNS de la VPN")
    }

    @Test
    // Sin «*» en el nombre: en Windows no vale en el nombre del archivo de la clase.
    fun `with a proxy rule for every uci_cu name an internal site still goes direct and a public one through the proxy`() =
        runTest(UnconfinedTestDispatcher()) {
            connect(rulesProfile, vpn = start)

            askDns("drst.uci.cu")
            assertEquals(0 to "10.0.0.13", lastAnswer(), "el proxy no abre lo interno: va directo")

            askDns("www.uci.cu", port = 41001)
            assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(lastAnswer().second))!!), "lo público, por el proxy")
            assertTrue(session.sent.isEmpty())
        }

    @Test
    fun `a slow answer arrives later without holding the tunnel`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        transport.gate = CompletableDeferred()

        askDns("drst.uci.cu")
        advanceTimeBy(100)
        assertTrue(tun.written.isEmpty(), "todavía no")

        transport.gate!!.complete(Unit)
        runCurrent()
        assertEquals(0 to "10.0.0.13", lastAnswer())
    }

    @Test
    fun `a slow answer that is not internal goes back to the usual path`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        transport.gate = CompletableDeferred()

        askDns("www.uci.cu")
        advanceTimeBy(100)
        transport.gate!!.complete(Unit)
        runCurrent()
        assertTrue(tun.written.isEmpty(), "espera al siguiente paquete del bucle")

        askDns("www.google.com", port = 41001)

        val answers = tun.written.map { packet -> UdpDatagram.parse(Ipv4Packet.parse(packet)!!)!!.destinationPort }
        assertEquals(listOf(41000, 41001), answers, "primero la apartada, luego la nueva")
        assertTrue(fakeSubnet.contains(parseIpv4(assertNotNull(lastAnswerAt(0).second))!!))
    }

    private fun lastAnswerAt(index: Int): Pair<Int, String?> {
        val payload = UdpDatagram.parse(Ipv4Packet.parse(tun.written[index])!!)!!.payload
        return (payload.readU16(2) and 0x000F) to if (payload.readU16(6) > 0) formatIpv4(payload.readInt(payload.size - 4)) else null
    }

    @Test
    fun `through Otro País without rules the local network is answered here and the rest goes to the VPN`() =
        runTest(UnconfinedTestDispatcher()) {
            connect(profile.copy(countryExit = CountryExit.OpenVpn("pais.ovpn", "vpnuser")), vpn = start)
            val vpnDns = parseIpv4("10.98.0.1")!!
            val address = parseIpv4("10.98.0.3")!!
            fun ask(name: String, port: Int) =
                send(buildUdpPacket(address, vpnDns, sourcePort = port, destinationPort = 53, payload = dnsQuery(name, id = 0x4242)))

            ask("drst.uci.cu", 41000)
            assertEquals(0 to "10.0.0.13", lastAnswer())
            assertTrue(session.sent.isEmpty())

            ask("wpad.uci.cu", 41001)
            assertEquals(3 to null, lastAnswer())

            ask("www.google.com", 41002)
            assertEquals(1, session.sent.size, "lo demás va a la VPN tal cual")
            assertTrue(platformTunnel.config!!.routes.none { it.contains(parseIpv4("10.0.0.13")!!) })
        }
}
