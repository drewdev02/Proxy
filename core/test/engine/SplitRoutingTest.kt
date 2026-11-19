package org.uci.proxy.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.dns.dnsQuery
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.VpnProfile
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.PROTOCOL_TCP
import org.uci.proxy.core.packet.PROTOCOL_UDP
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.internetChecksum
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.packet.sumWords
import org.uci.proxy.core.packet.writeInt
import org.uci.proxy.core.packet.writeU16
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.vpn.OpenVpnPush
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnExit
import org.uci.proxy.core.vpn.VpnSession
import org.uci.proxy.core.vpn.VpnStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val VPN_ADDRESS = parseIpv4("10.98.0.9")!!
private val VPN_DNS = parseIpv4("10.98.0.1")!!

/** Una sesión VPN en memoria: lo que la prueba meta en [fromServer] llega al motor, y lo que el motor envíe queda en [sent]. */
private class ScriptedVpnSession : VpnSession {
    override val push = OpenVpnPush(
        address = "10.98.0.9", netmask = "255.255.0.0", dnsServers = listOf("10.98.0.1"), peerId = 3, cipher = "AES-256-GCM",
        tunMtu = 1500, pingSeconds = 10, pingRestartSeconds = 60, redirectGateway = true,
    )
    val sent = mutableListOf<ByteArray>()
    val fromServer = Channel<ByteArray>(Channel.UNLIMITED)
    var closed = false
        private set

    override suspend fun writePacket(packet: ByteArray) {
        sent += packet
    }

    override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) {
        for (packet in fromServer) onPacket(packet)
    }

    override fun close() {
        closed = true
        fromServer.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SplitRoutingTest {
    private val logger = RecordingLogger()
    private val stateStore = TunnelStateStore()
    private val platformTunnel = FakePlatformTunnel()
    private val tun get() = platformTunnel.tun
    private val proxyTunnel = FakeTunnelConnection()
    private val connector = FakeProxyConnector.connectingTo(proxyTunnel)
    private val start = VpnStart("client\n", "vpnuser", "vpnpass")

    private val rules = RoutingRules(
        enabled = true,
        defaultExit = RouteExit.PROXY,
        rules = listOf(
            RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN),
            RouteRule(RuleKind.DOMAIN, "ads.example", RouteExit.BLOCK),
            RouteRule(RuleKind.IP, "200.55.0.0/16", RouteExit.DIRECT),
        ),
    )
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey", VpnProfile("pais.ovpn", "vpnuser"), rules)

    /** La app dueña de cada conexión, por su puerto de origen. */
    private val appsByPort = mutableMapOf<Int, List<String>>()
    private val owner = ConnectionOwner { _, _, sourcePort, _, _ -> appsByPort[sourcePort].orEmpty() }

    /** Lo que devuelve cada intento de abrir la VPN, por orden; el último se repite. */
    private val results = ArrayDeque<VpnConnectResult>()
    private val sessions = mutableListOf<ScriptedVpnSession>()
    private var attempts = 0
    private val exit = VpnExit { _, _, _, _ ->
        attempts++
        val next = if (results.size > 1) results.removeFirst() else results.firstOrNull() ?: VpnConnectResult.Connected(ScriptedVpnSession())
        // Cada conexión correcta es una sesión nueva.
        if (next is VpnConnectResult.Connected) VpnConnectResult.Connected(ScriptedVpnSession().also { sessions += it }) else next
    }

    private fun TestScope.connect(profile: ProxyProfile = this@SplitRoutingTest.profile, vpn: VpnStart? = start) = backgroundScope.launch {
        val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher(testScheduler))
        val runner = TunnelRunner(
            profiles = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger),
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
            engineFactory = TunnelEngineFactory(listOf(connector), { ByteArray(it) { 1 } }, testScheduler.timeSource, logger, owner = owner),
            hostResolver = { "10.8.0.20" },
            stateStore = stateStore,
            dispatchers = dispatchers,
            logger = logger,
            vpnExit = exit,
        )
        runner.run(platformTunnel, profile, "x", vpn = vpn)
    }.also { runCurrent() }

    private val app get() = parseIpv4(assertNotNull(platformTunnel.config).address)!!
    private val tunDns get() = parseIpv4(assertNotNull(platformTunnel.config).dnsServer)!!
    private val session get() = sessions.last()

    private fun TestScope.send(packet: ByteArray) {
        tun.inject(packet)
        runCurrent()
    }

    private fun TestScope.fromVpn(packet: ByteArray) {
        session.fromServer.trySend(packet)
        runCurrent()
    }

    private fun TestScope.askDns(name: String, port: Int = 41000) =
        send(buildUdpPacket(app, tunDns, sourcePort = port, destinationPort = 53, payload = dnsQuery(name, id = 0x4242)))

    private fun syn(destination: Int, port: Int = 443, sourcePort: Int = 50000) =
        buildTcpPacket(app, destination, sourcePort, port, sequence = 100, acknowledgment = 0, flags = TcpFlags.SYN, window = 65535)

    /** La pila TCP del túnel (el camino del proxy) contestó al SYN en nombre de [destination]. */
    private fun assertAnsweredByProxyStack(destination: Int) {
        val reply = lastToApp()
        val segment = assertNotNull(TcpSegment.parse(reply))
        assertEquals(destination, reply.source)
        assertTrue(segment.has(TcpFlags.SYN) && segment.has(TcpFlags.ACK), "SYN-ACK de la pila local")
    }

    /** La dirección inventada que el DNS del túnel da a [name]. */
    private fun TestScope.fakeAddressOf(name: String): Int {
        askDns(name, port = 42000)
        val payload = UdpDatagram.parse(lastToApp())!!.payload
        return payload.readInt(payload.size - 4)
    }

    private fun lastToApp() = assertNotNull(Ipv4Packet.parse(tun.written.last()))

    private fun assertValidChecksums(bytes: ByteArray) {
        val packet = assertNotNull(Ipv4Packet.parse(bytes))
        assertEquals(0, internetChecksum(sumWords(bytes, 0, packet.headerLength)), "checksum IP")
        val pseudoHeader = sumWords(bytes, 12, 8) + packet.protocol + packet.payloadLength
        assertEquals(0, internetChecksum(pseudoHeader + sumWords(bytes, packet.headerLength, packet.payloadLength)), "checksum TCP/UDP")
    }

    /** La respuesta del DNS de la VPN a la consulta 0x4242 por [name], con la IP [address]. */
    private fun vpnDnsAnswer(name: String, address: String?, port: Int = 41000, id: Int = 0x4242): ByteArray {
        val query = dnsQuery(name, id = id)
        if (address == null) {
            query.writeU16(2, 0x8183) // NXDOMAIN, sin respuestas
            return buildUdpPacket(VPN_DNS, VPN_ADDRESS, sourcePort = 53, destinationPort = port, payload = query)
        }
        val answer = ByteArray(16).also {
            it.writeU16(0, 0xC00C)
            it.writeU16(2, 1)
            it.writeU16(4, 1)
            it.writeInt(6, 300)
            it.writeU16(10, 4)
            it.writeInt(12, parseIpv4(address)!!)
        }
        val message = query + answer
        message.writeU16(2, 0x8180)
        message.writeU16(6, 1)
        return buildUdpPacket(VPN_DNS, VPN_ADDRESS, sourcePort = 53, destinationPort = port, payload = message)
    }

    @Test
    fun `names that go abroad are asked to the VPN's DNS and the real answer reaches the app`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("www.netflix.com")
        val query = assertNotNull(Ipv4Packet.parse(session.sent.single()))
        assertEquals(VPN_ADDRESS, query.source, "sale con la dirección de la VPN")
        assertEquals(VPN_DNS, query.destination, "al DNS de la VPN")
        assertValidChecksums(session.sent.single())
        assertTrue(tun.written.isEmpty(), "no se contesta con el DNS falso")

        fromVpn(vpnDnsAnswer("www.netflix.com", "52.1.2.3"))
        val reply = lastToApp()
        assertEquals(tunDns, reply.source, "como si contestara el DNS del túnel")
        assertEquals(app, reply.destination)
        assertValidChecksums(tun.written.last())
        val payload = UdpDatagram.parse(reply)!!.payload
        assertEquals("52.1.2.3", formatIpv4(payload.readInt(payload.size - 4)))
    }

    @Test
    fun `other names get the fake DNS as always`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        askDns("www.uci.cu")

        assertTrue(session.sent.isEmpty())
        val payload = UdpDatagram.parse(lastToApp())!!.payload
        assertTrue(formatIpv4(payload.readInt(payload.size - 4)).startsWith("10."), "una dirección inventada")
    }

    @Test
    fun `a connection to an address learned from the VPN goes through the VPN both ways`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        askDns("www.netflix.com")
        fromVpn(vpnDnsAnswer("www.netflix.com", "52.1.2.3"))
        val server = parseIpv4("52.1.2.3")!!

        send(syn(server))
        val out = assertNotNull(Ipv4Packet.parse(session.sent.last()))
        assertEquals(PROTOCOL_TCP, out.protocol)
        assertEquals(VPN_ADDRESS, out.source)
        assertEquals(server, out.destination)
        assertValidChecksums(session.sent.last())
        assertTrue(connector.targets.isEmpty(), "no pasa por el proxy")

        val synAck = buildTcpPacket(server, VPN_ADDRESS, 443, 50000, 7000, 101, TcpFlags.SYN or TcpFlags.ACK, 65535)
        fromVpn(synAck)
        val back = lastToApp()
        assertEquals(server, back.source)
        assertEquals(app, back.destination, "vuelve a la dirección de la interfaz")
        assertValidChecksums(tun.written.last())
    }

    @Test
    fun `UDP to an address learned from the VPN also goes through it`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        askDns("video.netflix.com")
        fromVpn(vpnDnsAnswer("video.netflix.com", "52.1.2.4"))

        send(buildUdpPacket(app, parseIpv4("52.1.2.4")!!, 50001, 443, ByteArray(1200)))

        val out = assertNotNull(Ipv4Packet.parse(session.sent.last()))
        assertEquals(PROTOCOL_UDP, out.protocol, "QUIC sale por la VPN en vez de rechazarse")
        assertValidChecksums(session.sent.last())
    }

    @Test
    fun `blocked names are refused at once`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        askDns("tracker.ads.example")
        val payload = UdpDatagram.parse(lastToApp())!!.payload
        val fake = payload.readInt(payload.size - 4)

        send(syn(fake))

        val reply = lastToApp()
        val segment = assertNotNull(TcpSegment.parse(reply))
        assertTrue(segment.has(TcpFlags.RST))
        assertTrue(connector.targets.isEmpty())
        assertTrue(session.sent.size == 0)
    }

    @Test
    fun `direct ranges are left out of the interface`() = runTest(UnconfinedTestDispatcher()) {
        connect()

        val routes = assertNotNull(platformTunnel.config).routes
        assertFalse(routes.any { it.contains(parseIpv4("200.55.1.1")!!) }, "lo directo no entra en el túnel")
        assertTrue(routes.any { it.contains(parseIpv4("8.8.8.8")!!) })
    }

    @Test
    fun `if the VPN does not connect the tunnel still starts and its names use the proxy`() = runTest(UnconfinedTestDispatcher()) {
        results += VpnConnectResult.Failed(TunnelFailure.VPN_SERVER_UNREACHABLE)
        connect()

        assertEquals(TunnelState.Connected("p1", vpnDown = true), stateStore.state.value)
        askDns("www.netflix.com")
        assertEquals(1, tun.written.size, "contestado con el DNS falso, para ir por el proxy")
    }

    @Test
    fun `when the VPN drops it warns, falls back to the proxy and comes back on retry`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        val first = session

        first.fromServer.close()
        runCurrent()
        assertTrue(first.closed)
        assertEquals(TunnelState.Connected("p1", vpnDown = true), stateStore.state.value)
        assertTrue(platformTunnel.config != null && !tun.closed, "el túnel sigue en pie")
        askDns("www.netflix.com")
        assertTrue(first.sent.isEmpty())

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
        askDns("www.netflix.com", port = 41001)
        assertEquals(1, session.sent.size, "la nueva sesión recibe la consulta")
    }

    @Test
    fun `bad VPN credentials are not retried`() = runTest(UnconfinedTestDispatcher()) {
        results += VpnConnectResult.Failed(TunnelFailure.VPN_AUTH_FAILED)
        connect()

        advanceTimeBy(300.seconds)
        runCurrent()

        assertEquals(1, attempts)
        assertEquals(TunnelState.Connected("p1", vpnDown = true), stateStore.state.value)
    }

    @Test
    fun `rules without the VPN never open it`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = RoutingRules(enabled = true, rules = listOf(RouteRule(RuleKind.DOMAIN, "ads.example", RouteExit.BLOCK)))))

        assertEquals(0, attempts)
        assertEquals(TunnelState.Connected("p1"), stateStore.state.value)
    }

    @Test
    fun `without rules a profile with a VPN still sends everything through it`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = RoutingRules()))

        assertEquals(1, attempts)
        assertEquals("10.98.0.9", assertNotNull(platformTunnel.config).address, "el modo de siempre: la interfaz es de la VPN")
    }

    @Test
    fun `stopping the tunnel closes the VPN`() = runTest(UnconfinedTestDispatcher()) {
        val job = connect()
        val current = session

        job.cancel()
        runCurrent()

        assertTrue(current.closed)
        assertTrue(tun.closed)
        assertEquals(TunnelState.Disconnected, stateStore.state.value)
    }

    private val appRules = RoutingRules(
        enabled = true,
        rules = listOf(
            RouteRule(RuleKind.APP, "com.netflix.mediaclient", RouteExit.VPN),
            RouteRule(RuleKind.APP, "com.tiktok", RouteExit.BLOCK),
            RouteRule(RuleKind.APP, "cu.uci.correo", RouteExit.DIRECT),
        ),
    )

    @Test
    fun `an app that goes abroad has its fake address resolved by the VPN and its connection translated both ways`() =
        runTest(UnconfinedTestDispatcher()) {
            connect(profile.copy(routing = appRules))
            val fake = fakeAddressOf("www.netflix.com")
            appsByPort[50000] = listOf("com.netflix.mediaclient")

            // El primer SYN dispara la resolución por la VPN y se descarta: la app lo reintentará.
            send(syn(fake, sourcePort = 50000))
            val query = assertNotNull(Ipv4Packet.parse(session.sent.single()))
            val queryDns = UdpDatagram.parse(query)!!
            assertEquals(VPN_DNS, query.destination)
            assertEquals(53, queryDns.destinationPort)
            assertTrue(connector.targets.isEmpty(), "ni por el proxy")
            fromVpn(vpnDnsAnswer("www.netflix.com", "52.1.2.3", port = queryDns.sourcePort, id = queryDns.payload.readU16(0)))
            assertTrue(tun.written.size == 1, "la respuesta a la resolución propia no llega a las apps")

            // El reintento sale ya hacia la IP real.
            send(syn(fake, sourcePort = 50000))
            val out = assertNotNull(Ipv4Packet.parse(session.sent.last()))
            assertEquals(VPN_ADDRESS, out.source)
            assertEquals("52.1.2.3", formatIpv4(out.destination))
            assertValidChecksums(session.sent.last())

            // Y la respuesta vuelve con la dirección inventada, que es a la que la app conectó.
            fromVpn(buildTcpPacket(parseIpv4("52.1.2.3")!!, VPN_ADDRESS, 443, 50000, 7000, 101, TcpFlags.SYN or TcpFlags.ACK, 65535))
            val back = lastToApp()
            assertEquals(fake, back.source)
            assertEquals(app, back.destination)
            assertValidChecksums(tun.written.last())
        }

    @Test
    fun `other apps going to the same name still use the proxy`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = appRules))
        val fake = fakeAddressOf("www.netflix.com")
        appsByPort[50001] = listOf("com.android.chrome")

        send(syn(fake, sourcePort = 50001))

        assertTrue(session.sent.isEmpty())
        assertAnsweredByProxyStack(fake)
    }

    @Test
    fun `a blocked app is refused at once`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = appRules))
        val fake = fakeAddressOf("www.tiktok.com")
        appsByPort[50002] = listOf("com.tiktok")

        send(syn(fake, sourcePort = 50002))

        assertTrue(assertNotNull(TcpSegment.parse(lastToApp())).has(TcpFlags.RST))
        assertTrue(connector.targets.isEmpty())
    }

    @Test
    fun `a name the VPN cannot resolve sends the app's connection through the proxy`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = appRules))
        val fake = fakeAddressOf("nada.invalid")
        appsByPort[50003] = listOf("com.netflix.mediaclient")

        send(syn(fake, sourcePort = 50003))
        val query = UdpDatagram.parse(assertNotNull(Ipv4Packet.parse(session.sent.single())))!!
        fromVpn(vpnDnsAnswer("nada.invalid", null, port = query.sourcePort, id = query.payload.readU16(0)))
        send(syn(fake, sourcePort = 50003))

        assertEquals(1, session.sent.size, "solo la consulta")
        assertAnsweredByProxyStack(fake)
    }

    @Test
    fun `direct apps are left out of the interface`() = runTest(UnconfinedTestDispatcher()) {
        connect(profile.copy(routing = appRules))

        assertEquals(listOf("cu.uci.correo"), assertNotNull(platformTunnel.config).excludedApps)
    }
}
