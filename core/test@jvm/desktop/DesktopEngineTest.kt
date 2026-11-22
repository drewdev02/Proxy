package org.uci.proxy.core.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.uci.proxy.core.dns.dnsQuery
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.fakes.FakePlatformTunnel
import org.uci.proxy.core.fakes.FakeProxyConnector
import org.uci.proxy.core.fakes.FakeTunnelConnection
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.log.BufferedLogger
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.NameOutcome
import org.uci.proxy.core.data.StoredSettingsRepository
import org.uci.proxy.core.model.LogLevel
import kotlin.time.Clock
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.fakes.fakeEngines
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.data.StoredProfileRepository
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.traffic.TrafficDelta
import org.uci.proxy.core.model.TrafficUsage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class EngineProtocolTest {
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

    @Test
    fun `a start order survives the round trip including awkward passwords`() {
        val start = EngineStart(profile, password = "con espacios \"comillas\" ñ \\ y\nsalto")

        val line = EngineProtocol.encodeStart(start)

        assertFalse('\n' in line, "un mensaje es una sola línea")
        assertEquals(start, EngineProtocol.decodeStart(line))
    }

    @Test
    fun `the settings travel with the start order and default to on when absent`() {
        val off = EngineStart(profile, "x", AppSettings(answerConnectivityChecks = false))

        assertEquals(off, EngineProtocol.decodeStart(EngineProtocol.encodeStart(off)))

        val withoutSettings = EngineProtocol.encodeStart(EngineStart(profile, "x")).replace(Regex(""","settings":\{[^}]*\}"""), "")
        assertTrue(EngineProtocol.decodeStart(withoutSettings)!!.settings.answerConnectivityChecks)
    }

    @Test
    fun `a start order carries the VPN along`() {
        val start = EngineStart(
            profile, "x",
            countryExit = CountryExitStart(
                CountryExit.OpenVpn("pais.ovpn", "usuario"),
                CountryExitSecrets.OpenVpn("client\nremote 192.0.2.1 443\n<ca>\n...\n</ca>\n", "con \"comillas\" y ñ"),
            ),
        )

        val line = EngineProtocol.encodeStart(start)

        assertFalse('\n' in line)
        assertEquals(start, EngineProtocol.decodeStart(line))
    }

    @Test
    fun `a name service report survives the round trip and bad lines are rejected`() {
        val line = EngineProtocol.encodeNameStat("sslip", NameOutcome.REFUSED)

        assertEquals("sslip" to NameOutcome.REFUSED, EngineProtocol.decodeNameStat(line))
        assertNull(EngineProtocol.decodeNameStat("NAMESTAT sslip"))
        assertNull(EngineProtocol.decodeNameStat("NAMESTAT sslip A_MEDIAS"))
        assertNull(EngineProtocol.decodeNameStat("NAMESTAT  WORKED"))
        assertNull(EngineProtocol.decodeNameStat("STATE connected"))
    }

    @Test
    fun `a traffic measure survives the round trip and bad lines are rejected`() {
        val delta = TrafficDelta(
            millis = 1_002,
            up = 40,
            down = 900,
            connections = 3,
            usage = listOf(TrafficUsage(RouteExit.PROXY, "github.com", null, 40, 100), TrafficUsage(RouteExit.VPN, null, null, 0, 800)),
        )

        assertEquals(delta, EngineProtocol.decodeTraffic(EngineProtocol.encodeTraffic(delta)))
        assertNull(EngineProtocol.decodeTraffic("TRAFFIC {no es json"))
        assertNull(EngineProtocol.decodeTraffic("""TRAFFIC {"millis":1,"up":0,"down":0,"connections":0,"usage":[{"exit":"NUEVA","host":null,"app":null,"up":0,"down":0}]}"""))
        assertNull(EngineProtocol.decodeTraffic("STATE connected"))
    }

    @Test
    fun `lines that are not a valid start are rejected`() {
        assertNull(EngineProtocol.decodeStart("STOP"))
        assertNull(EngineProtocol.decodeStart("START {no es json"))
        assertNull(EngineProtocol.decodeStart("START {}"))
    }

    @Test
    fun `a log message survives the round trip even when it has several lines and odd characters`() {
        val entry = LogEntry(7, 1_700_000_000_123, LogLevel.WARN, "Relay", "No abrió ñandú.example:443\n\tcausa: \"tiempo agotado\" \\ fin")

        val line = EngineProtocol.encodeLog(entry)

        assertFalse('\n' in line, "un mensaje es una sola línea")
        assertEquals(EngineLog(1_700_000_000_123, "WARN", "Relay", entry.message), EngineProtocol.decodeLog(line))
    }

    @Test
    fun `lines that are not a valid log message are rejected`() {
        assertNull(EngineProtocol.decodeLog("STATE connected"))
        assertNull(EngineProtocol.decodeLog("LOG {no es json"))
        assertNull(EngineProtocol.decodeLog("LOG {\"epochMillis\":1}"))
    }

    @Test
    fun `engine states survive the round trip`() {
        fun roundTrip(state: TunnelState) = EngineProtocol.decodeState(EngineProtocol.encodeState(state)!!, "p1")

        assertEquals(TunnelState.Connected("p1"), roundTrip(TunnelState.Connected("otro-id-del-motor")))
        assertEquals(TunnelState.Connected("p1", vpnDown = true), roundTrip(TunnelState.Connected("p1", vpnDown = true)))
        val viaProxy = TunnelState.Connected("p1", viaProxy = listOf("example.com", "www.google.com"))
        assertEquals(viaProxy, roundTrip(viaProxy))
        assertEquals(TunnelState.Disconnected, roundTrip(TunnelState.Disconnected))
        TunnelFailure.entries.forEach { assertEquals(TunnelState.Failed(it), roundTrip(TunnelState.Failed(it))) }
    }

    @Test
    fun `connecting is never sent and unknown lines are ignored`() {
        assertNull(EngineProtocol.encodeState(TunnelState.Connecting("p1")))
        assertNull(EngineProtocol.decodeState("STATE failed ALGO_NUEVO", "p1"))
        assertNull(EngineProtocol.decodeState("cualquier cosa", "p1"))
    }
}

class MacElevatedEngineLauncherTest {
    @Test
    fun `arguments are quoted for the shell and the whole line for AppleScript`() {
        val command = MacElevatedEngineLauncher.osascriptCommand(
            listOf("/Library/Java/bin/java", "-cp", "/Users/a b/app's \"libs\"/*", "org.uci.proxy.MainKt", "--engine"),
        )

        assertEquals(listOf("/usr/bin/osascript", "-e"), command.take(2))
        assertEquals(
            "do shell script \"'/Library/Java/bin/java' '-cp' '/Users/a b/app'\\\\''s \\\"libs\\\"/*' 'org.uci.proxy.MainKt' '--engine'\" " +
                "with prompt \"ProxyUCI necesita permisos de administrador para crear la conexión de red.\" " +
                "with administrator privileges",
            command[2],
        )
    }

    @Test
    fun `shell quoting leaves nothing for the shell to interpret`() {
        assertEquals("'\$HOME; rm -rf /'", MacElevatedEngineLauncher.shellQuote("\$HOME; rm -rf /"))
        assertEquals("'it'\\''s'", MacElevatedEngineLauncher.shellQuote("it's"))
    }

    @Test
    fun `option values are read by name`() {
        val args = listOf("--engine", "--port", "5000", "--token-file", "/tmp/t")

        assertEquals("5000", EngineArguments.value(args, EngineArguments.PORT))
        assertEquals("/tmp/t", EngineArguments.value(args, EngineArguments.TOKEN_FILE))
        assertNull(EngineArguments.value(args, EngineArguments.ROUTES))
        assertNull(EngineArguments.value(listOf("--port"), EngineArguments.PORT))
    }
}

/**
 * La ventana y el motor de verdad, hablando por un socket local real. Lo único simulado es el
 * lanzamiento con privilegios (el motor corre en este mismo proceso) y la interfaz de red.
 */
class DesktopTunnelIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchers = AppDispatchers(background = Dispatchers.IO)
    private val logger = RecordingLogger()
    private val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val repository = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger)

    // Lado del motor. Su registro y el de la ventana son búferes distintos, como lo son sus procesos.
    private val engineLogs = LogBuffer()
    private val windowLogs = LogBuffer()
    private val engineLogger = BufferedLogger(logger, engineLogs, Clock.System)
    private val platformTunnel = FakePlatformTunnel()
    private val connector = FakeProxyConnector.connectingTo(FakeTunnelConnection())
    private var receivedStart: EngineStart? = null
    private var tokenFileSeen: Path? = null

    /** Qué hace el "lanzamiento con privilegios" en cada prueba. */
    private var launchBehaviour: (port: Int, tokenFile: Path) -> EngineProcess = ::launchInProcess

    private fun engineSession(maxDuration: Duration? = null): EngineSession {
        val stateStore = TunnelStateStore()
        val runner = TunnelRunner(
            profiles = repository,
            addressPlanner = TunAddressPlanner(localAddresses = { emptyList() }, randomBytes = { ByteArray(it) { 56 } }),
            engineFactory = TunnelEngineFactory(listOf(connector), { ByteArray(it) { 1 } }, TimeSource.Monotonic, logger),
            hostResolver = { host -> "10.8.0.20".also { receivedHost = host } },
            stateStore = stateStore,
            dispatchers = dispatchers,
            logger = engineLogger,
            engines = fakeEngines { context, start ->
                receivedVpn = Triple(context.proxy, context.proxyPassword, start)
                vpnResult
            },
        )
        return EngineSession(runner, stateStore, platformTunnel, Dispatchers.IO, engineLogger, engineLogs, maxDuration, nameForwarder)
    }

    private val nameForwarder = EngineNameForwarder()
    private val windowNameStatus = java.util.concurrent.CopyOnWriteArrayList<Pair<String, NameOutcome>>()
    private var receivedVpn: Triple<ProxyProfile, String, CountryExitStart>? = null
    private var vpnResult: VpnConnectResult = VpnConnectResult.Failed(TunnelFailure.VPN_AUTH_FAILED)
    private var receivedHost: String? = null
    private var engineMaxDuration: Duration? = null

    private fun launchInProcess(port: Int, tokenFile: Path): EngineProcess {
        tokenFileSeen = tokenFile
        val token = Files.readString(tokenFile)
        val job = scope.launch {
            Socket(InetAddress.getLoopbackAddress(), port).use { engineSession(engineMaxDuration).serve(it, token) }
        }
        return object : EngineProcess {
            override val isAlive get() = job.isActive

            override fun destroy() {
                job.cancel()
            }
        }
    }

    private val controller = DesktopTunnelController(
        profiles = repository,
        launcher = EngineLauncher { port, tokenFile -> launchBehaviour(port, tokenFile) },
        randomBytes = { ByteArray(it) { 7 } },
        dispatchers = dispatchers,
        logger = logger,
        logBuffer = windowLogs,
        settings = StoredSettingsRepository(InMemoryKeyValueStore()),
        nameStatus = { id, outcome -> windowNameStatus += id to outcome },
        launchTimeout = 5.seconds,
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
    }

    private suspend fun awaitState(predicate: (TunnelState) -> Boolean): TunnelState =
        withTimeout(10.seconds) { controller.state.first(predicate) }

    @Test
    fun `what the engine logs reaches the window's log, including what it logged before the window asked`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        engineLogs.append(1_000, LogLevel.INFO, "Motor", "arrancó antes de recibir la orden")

        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }
        engineLogs.append(2_000, LogLevel.WARN, "Relay", "El proxy no abrió example.org:443")

        val received = withTimeout(10.seconds) {
            windowLogs.entries.first { entries -> entries.any { "example.org" in it.message } }
        }
        val messages = received.map { it.message }
        assertTrue("arrancó antes de recibir la orden" in messages)
        assertTrue(messages.any { "Túnel activo" in it }, "también llega lo que registra el propio túnel: $messages")
        val warning = received.single { "example.org" in it.message }
        assertEquals(LogLevel.WARN, warning.level)
        assertEquals("Relay", warning.tag)
        assertEquals(2_000, warning.epochMillis)
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }
    }

    @Test
    fun `a profile with a VPN sends its file and credentials to the engine and a failure comes back by name`() = runBlocking<Unit> {
        val withVpn = profile.copy(countryExit = CountryExit.OpenVpn("pais.ovpn", "vpnuser"))
        repository.save(withVpn, "proxypass", CountryExitSecrets.OpenVpn("client\n", "vpnpass"))

        controller.connect(withVpn.id)
        val state = awaitState { it is TunnelState.Failed }

        val (proxy, proxyPassword, vpn) = assertNotNull(receivedVpn)
        assertEquals("proxypass", proxyPassword)
        assertEquals(CountryExitStart(withVpn.countryExit!!, CountryExitSecrets.OpenVpn("client\n", "vpnpass")), vpn)
        assertEquals("10.8.0.20", proxy.host, "el motor resolvió el proxy antes de abrir la VPN")
        assertEquals(TunnelState.Failed(TunnelFailure.VPN_AUTH_FAILED), state)
    }

    @Test
    fun `a profile whose VPN file is missing fails in the window without launching the engine`() = runBlocking<Unit> {
        val withVpn = profile.copy(countryExit = CountryExit.OpenVpn("pais.ovpn", "vpnuser"))
        repository.save(withVpn, "proxypass", exitSecrets = null)

        controller.connect(withVpn.id)

        assertEquals(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID), controller.state.value)
        assertNull(receivedVpn)
        assertNull(tokenFileSeen, "no se lanzó el motor, que pediría privilegios para nada")
    }

    @Test
    fun `what the engine says about a name service reaches the window`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }

        nameForwarder.report("sslip", NameOutcome.REFUSED)
        nameForwarder.report("nip", NameOutcome.WORKED)

        withTimeout(10.seconds) { while (windowNameStatus.size < 2) delay(20) }
        assertEquals(listOf("sslip" to NameOutcome.REFUSED, "nip" to NameOutcome.WORKED), windowNameStatus.toList())
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }
    }

    @Test
    fun `a log message that arrives after the tunnel stopped is not lost`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }

        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }

        assertTrue(windowLogs.entries.value.any { "Túnel detenido" in it.message }, "el último mensaje del túnel llega antes del cierre")
    }

    @Test
    fun `connecting launches the engine, hands it the profile and reports connected`() = runBlocking<Unit> {
        repository.save(profile, "secreta")

        controller.connect(profile.id)

        assertEquals(TunnelState.Connected("p1"), awaitState { it is TunnelState.Connected })
        assertEquals("UCI", platformTunnel.config?.sessionName)
        assertEquals("proxy.uci.cu", receivedHost, "el motor recibió el perfil completo")
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }
    }

    @Test
    fun `the tunnel run by the engine really works while connected`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }

        val app = parseIpv4("10.57.0.1")!!
        val dns = parseIpv4("10.57.0.2")!!
        platformTunnel.tun.inject(buildUdpPacket(app, dns, 41000, 53, dnsQuery("uci.cu")))

        withTimeout(5.seconds) { while (platformTunnel.tun.written.isEmpty()) kotlinx.coroutines.delay(20) }
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }
    }

    @Test
    fun `disconnecting stops the engine and closes the interface`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }

        controller.disconnect()

        assertEquals(TunnelState.Disconnected, awaitState { it == TunnelState.Disconnected })
        assertTrue(platformTunnel.tun.closed)
    }

    @Test
    fun `the token file is private and removed when the session ends`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }
        val tokenFile = tokenFileSeen!!
        assertOnlyOwnerCanAccess(tokenFile)

        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }

        assertFalse(tokenFile.exists())
    }

    @Test
    fun `an engine that cannot create the interface is reported as a failure`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        platformTunnel.failWith = "no es root"

        controller.connect(profile.id)

        assertEquals(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE), awaitState { it is TunnelState.Failed })
    }

    @Test
    fun `cancelling the administrator prompt is reported as permission denied`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        // Como osascript cuando se cancela la contraseña: el proceso termina sin que el motor llegue a existir.
        launchBehaviour = { _, _ ->
            object : EngineProcess {
                override val isAlive = false

                override fun destroy() = Unit
            }
        }

        controller.connect(profile.id)

        assertEquals(TunnelState.Failed(TunnelFailure.VPN_PERMISSION_DENIED), awaitState { it is TunnelState.Failed })
    }

    @Test
    fun `a local process that connects without the token is not trusted with the profile`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        var intruderGot: String? = "sin respuesta"
        launchBehaviour = { port, _ ->
            val job = scope.launch {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.getOutputStream().write("token-inventado\n".toByteArray())
                    intruderGot = socket.getInputStream().bufferedReader().readLine()
                }
            }
            object : EngineProcess {
                override val isAlive get() = job.isActive

                override fun destroy() = job.cancel()
            }
        }

        controller.connect(profile.id)

        awaitState { it is TunnelState.Failed }
        kotlinx.coroutines.delay(200.milliseconds)
        assertNull(intruderGot, "no recibió ni el perfil ni la contraseña")
    }

    @Test
    fun `an engine that reaches its time limit disconnects on its own`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        engineMaxDuration = 300.milliseconds

        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }

        assertEquals(TunnelState.Disconnected, awaitState { it == TunnelState.Disconnected })
        assertTrue(platformTunnel.tun.closed)
    }

    @Test
    fun `connecting with an unknown profile fails without launching anything`() = runBlocking<Unit> {
        var launched = false
        launchBehaviour = { port, file -> launched = true; launchInProcess(port, file) }

        controller.connect("no-existe")

        assertEquals(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND), controller.state.value)
        assertFalse(launched)
    }

    @Test
    fun `after a session ends a new one can be started`() = runBlocking<Unit> {
        repository.save(profile, "secreta")
        controller.connect(profile.id)
        awaitState { it is TunnelState.Connected }
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }

        controller.connect(profile.id)

        assertEquals(TunnelState.Connected("p1"), awaitState { it is TunnelState.Connected })
        controller.disconnect()
        awaitState { it == TunnelState.Disconnected }
    }
}
