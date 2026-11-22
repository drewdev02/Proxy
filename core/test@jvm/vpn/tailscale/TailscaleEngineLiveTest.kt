package org.uci.proxy.core.vpn.tailscale

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.proxy.BasicAuthScheme
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.NtlmAuthScheme
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.ExitContext
import org.uci.proxy.core.vpn.ProxyDialer
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnSession
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * El motor Tailscale de punta a punta, con todo real menos Internet: la librería nativa (tools/tsbridge/build.sh), una red
 * de Tailscale de pruebas (control, DERP y nodo de salida: `tools/tsbridge/build.sh testnet`) y el proxy de pruebas con
 * NTLM (tools/test_proxy.py). La sesión se abre solo a través del proxy y una petición HTTP sale por el nodo de salida.
 *
 * Sin la librería o la red de pruebas no hace nada, salvo con PROXYUCI_REQUIRE_TAILSCALE=1 (el CI), que entonces falla.
 */
class TailscaleEngineLiveTest {
    private val processes = mutableListOf<Process>()
    private val closers = mutableListOf<() -> Unit>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = println("[$level] $tag: $message")
    }
    private val required = System.getenv("PROXYUCI_REQUIRE_TAILSCALE") == "1"
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "tools/tsbridge/build.sh").exists() }

    @AfterTest
    fun cleanUp() {
        processes.forEach { it.destroy() }
        processes.forEach { it.waitFor() }
        closers.forEach { runCatching(it) }
    }

    private fun skip(reason: String) {
        if (required) fail("Falta para la prueba de Tailscale: $reason")
        println("TailscaleEngineLiveTest: $reason; no se prueba")
    }

    private class TestNet(val controlUrl: String, val authKey: String, val exitNode: String)

    private fun startTestNet(redirect: String): TestNet? {
        val exe = System.getenv("TSBRIDGE_TESTNET")?.let(::File)
            ?: root?.let { r -> listOf("testnet", "testnet.exe").map { File(r, "build/tsbridge/$it") }.firstOrNull { it.exists() } }
        if (exe == null || !exe.exists()) {
            skip("no está la red de pruebas (tools/tsbridge/build.sh testnet)")
            return null
        }
        val process = ProcessBuilder(exe.path, "-redirect", redirect).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        processes += process
        val line = process.inputStream.bufferedReader().readLine() ?: fail("la red de pruebas no arrancó")
        fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: fail("falta $name en $line")
        return TestNet(field("controlUrl"), field("authKey"), field("exitNode"))
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startProxy(): Int {
        val port = freePort()
        val script = File(root, "tools/test_proxy.py")
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val process = ProcessBuilder(python, "-u", script.path, "--auth", "ntlm", "--port", "$port", "--upstream")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
        processes += process
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return port
            Thread.sleep(100)
        }
        fail("el proxy de pruebas no escucha en $port")
    }

    /** Un «sitio de Internet»: a cada conexión le contesta una respuesta HTTP fija. */
    private fun startOrigin(accepted: AtomicInteger): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closers += { server.close() }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                thread(isDaemon = true) {
                    client.use { it.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 14\r\n\r\nhola-tailscale".toByteArray()) }
                }
            }
        }
        return server.localPort
    }

    private fun engine(bridge: TailscaleBridge): TailscaleEngine {
        val random = SecureRandom()
        val randomBytes = RandomBytes { ByteArray(it).also(random::nextBytes) }
        val selector = SelectorManager(Dispatchers.IO)
        closers += { selector.close() }
        val tcp = KtorTcpConnector(selector, AppDispatchers(background = Dispatchers.IO))
        val connector = HttpConnectConnector(tcp, listOf(NtlmAuthScheme(randomBytes, Clock.System), BasicAuthScheme()))
        return TailscaleEngine(bridge, ProxyDialer(listOf(connector), logger), selector, randomBytes, logger, Dispatchers.IO)
    }

    private fun bridge(): TailscaleBridge? {
        val (dir, file) = NativeTailscaleBridge.desktopResourceDir() ?: run {
            skip("no hay librería de Tailscale para este sistema")
            return null
        }
        val bridge = NativeTailscaleBridge.fromResource(dir, file, onLoadError = { println(it) })
        if (!bridge.isAvailable) {
            skip("no está la librería de Tailscale (tools/tsbridge/build.sh)")
            return null
        }
        return bridge
    }

    private fun context(proxyPort: Int, password: String = "secreta7") =
        ExitContext(ProxyProfile("p", "Pruebas", "127.0.0.1", proxyPort, ProxyProtocol.HTTP, "andrey"), password, AppSettings())

    @Test
    fun `a session opened only through an NTLM proxy carries an HTTP exchange through the exit node`() = runBlocking {
        val bridge = bridge() ?: return@runBlocking
        val accepted = AtomicInteger()
        val origin = startOrigin(accepted)
        val net = startTestNet("127.0.0.1:$origin") ?: return@runBlocking
        val proxyPort = startProxy()
        val engine = engine(bridge)

        // Primero, la búsqueda de nodos que usa el editor del perfil.
        val listing = engine.listExitNodes(context(proxyPort), CountryExit.Tailscale(net.controlUrl), CountryExitSecrets.Tailscale(net.authKey))
        assertIs<ExitNodeListing.Found>(listing, "búsqueda de nodos: $listing")
        assertEquals(listOf(net.exitNode), listing.nodes.map { it.name })

        val start = CountryExitStart(
            CountryExit.Tailscale(controlUrl = net.controlUrl, exitNode = net.exitNode, hostname = "kotlin"),
            CountryExitSecrets.Tailscale(net.authKey),
            stateDir = Files.createTempDirectory("ts-state").toString(),
        )
        val result = engine.connect(context(proxyPort), start)
        val session = assertIs<VpnConnectResult.Connected>(result, "conexión: $result").session
        try {
            assertTrue(session.network.address.startsWith("100."), session.network.address)
            assertEquals(listOf("100.100.100.100"), session.network.dnsServers)
            assertEquals(10, session.network.prefixLength)

            val body = httpThrough(session, "203.0.113.42", 80)
            assertTrue("hola-tailscale" in body, "respuesta: $body")
            assertTrue(accepted.get() > 0, "el sitio no recibió la conexión")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a large download goes through the exit node without stalling`() = runBlocking {
        val bridge = bridge() ?: return@runBlocking
        val size = 3 * 1024 * 1024
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        closers += { server.close() }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use {
                        it.getInputStream().read(ByteArray(1024))
                        it.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: $size\r\n\r\n".toByteArray())
                        it.getOutputStream().write(ByteArray(size) { i -> (i % 251).toByte() })
                    }
                }
            }
        }
        val net = startTestNet("127.0.0.1:${server.localPort}") ?: return@runBlocking
        val proxyPort = startProxy()
        val start = CountryExitStart(
            CountryExit.Tailscale(controlUrl = net.controlUrl, exitNode = net.exitNode),
            CountryExitSecrets.Tailscale(net.authKey),
            stateDir = Files.createTempDirectory("ts-state").toString(),
        )
        val session = assertIs<VpnConnectResult.Connected>(engine(bridge).connect(context(proxyPort), start)).session
        try {
            val started = System.currentTimeMillis()
            val response = httpBytesThrough(session, "203.0.113.42", 80, expected = size)
            val header = response.indexOfHeaderEnd()
            val bodyBytes = response.size - header
            println("Descarga de $bodyBytes bytes en ${System.currentTimeMillis() - started} ms")
            assertEquals(size, bodyBytes, "bytes del cuerpo")
            val expectedBody = ByteArray(size) { i -> (i % 251).toByte() }
            assertTrue(response.copyOfRange(header, response.size).contentEquals(expectedBody), "el cuerpo llegó alterado")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a wrong proxy password is reported as such`() = runBlocking {
        val bridge = bridge() ?: return@runBlocking
        val net = startTestNet("127.0.0.1:1") ?: return@runBlocking
        val proxyPort = startProxy()

        val result = engine(bridge).connect(
            context(proxyPort, password = "equivocada"),
            CountryExitStart(CountryExit.Tailscale(net.controlUrl, net.exitNode), CountryExitSecrets.Tailscale(net.authKey), Files.createTempDirectory("ts").toString()),
        )

        assertEquals(VpnConnectResult.Failed(TunnelFailure.PROXY_AUTH_FAILED), result)
    }

    /** Una conexión TCP mínima hecha a mano sobre la sesión: SYN, ACK, la petición, y lo que llegue hasta el FIN. */
    private fun ByteArray.indexOfHeaderEnd(): Int {
        for (i in 0..size - 4) {
            if (this[i] == '\r'.code.toByte() && this[i + 1] == '\n'.code.toByte() && this[i + 2] == '\r'.code.toByte() && this[i + 3] == '\n'.code.toByte()) return i + 4
        }
        return size
    }

    /** Como [httpThrough], pero con los bytes tal cual y parando en cuanto llegan [expected] bytes de cuerpo. */
    private suspend fun httpBytesThrough(session: VpnSession, host: String, port: Int, expected: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        httpThrough(session, host, port, quietMs = 30_000) { chunk ->
            out.write(chunk)
            val bytes = out.toByteArray()
            bytes.size - bytes.indexOfHeaderEnd() >= expected && bytes.indexOfHeaderEnd() < bytes.size
        }
        return out.toByteArray()
    }

    private suspend fun httpThrough(
        session: VpnSession,
        host: String,
        port: Int,
        quietMs: Long = 15_000,
        /** Recibe cada trozo de datos; si devuelve true, la descarga se da por terminada. */
        onData: (ByteArray) -> Boolean = { false },
    ): String = withTimeout(180_000) {
        val incoming = Channel<TcpSegment>(Channel.UNLIMITED)
        val reader = CoroutineScope(Dispatchers.IO).launch {
            session.receive { bytes ->
                val ip = Ipv4Packet.parse(bytes) ?: return@receive
                TcpSegment.parse(ip)?.let { incoming.send(it) }
            }
        }
        try {
            val me = parseIpv4(session.network.address)!!
            val dst = parseIpv4(host)!!
            val localPort = 41000
            var seq = 1000
            fun packet(flags: Int, ack: Int, payload: ByteArray = ByteArray(0)) =
                buildTcpPacket(me, dst, localPort, port, seq, ack, flags, 65535, payload)

            var synAck: TcpSegment? = null
            while (synAck == null) {
                session.writePacket(packet(TcpFlags.SYN, 0))
                synAck = withTimeoutOrNull(3_000) {
                    var s = incoming.receive()
                    while (!(s.has(TcpFlags.SYN) && s.has(TcpFlags.ACK))) s = incoming.receive()
                    s
                }
            }
            seq += 1
            var ack = synAck.sequence + 1
            val request = "GET / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray()
            session.writePacket(packet(TcpFlags.ACK, ack))
            session.writePacket(packet(TcpFlags.ACK or TcpFlags.PSH, ack, request))
            seq += request.size
            val body = StringBuilder()
            // Los segmentos que llegan adelantados se guardan hasta que llega el que falta, como hace un TCP de verdad:
            // sin esto, cada pérdida obliga al emisor a reenviar todo lo posterior.
            val pending = HashMap<Int, ByteArray>()
            var done = false
            while (!done) {
                val s = withTimeoutOrNull(quietMs) { incoming.receive() } ?: break
                if (s.payloadLength > 0 && s.sequence - ack >= 0) pending[s.sequence] = s.payload
                while (true) {
                    val next = pending.remove(ack) ?: break
                    body.append(next.decodeToString(throwOnInvalidSequence = false))
                    ack += next.size
                    if (onData(next)) {
                        session.writePacket(packet(TcpFlags.ACK or TcpFlags.FIN, ack))
                        done = true
                        break
                    }
                }
                if (done) break
                if (s.has(TcpFlags.FIN) && pending.isEmpty() && s.sequence + s.payloadLength == ack) {
                    ack += 1
                    session.writePacket(packet(TcpFlags.ACK or TcpFlags.FIN, ack))
                    break
                }
                session.writePacket(packet(TcpFlags.ACK, ack))
            }
            body.toString()
        } finally {
            reader.cancel()
        }
    }
}
