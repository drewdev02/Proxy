package org.uci.proxy.core.vpn

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.common.AppDispatchers
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Extremo a extremo contra un servidor `openvpn` de verdad, levantado con `tools/ovpn_test_server.sh` (certificados
 * propios, TCP, tls-crypt, AES-256-GCM y sin interfaz de red). Si `openvpn` no está instalado, las pruebas no hacen nada:
 * solo hace falta en quien quiera comprobar el protocolo contra la implementación de referencia.
 */
class OpenVpnLocalServerTest {
    private val openvpn = listOf(System.getenv("OPENVPN"), "/opt/homebrew/sbin/openvpn", "/usr/sbin/openvpn", "/usr/local/sbin/openvpn")
        .filterNotNull().map(::File).firstOrNull { it.canExecute() }
    private val script = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "tools/ovpn_test_server.sh") }.firstOrNull { it.exists() }

    private var server: Process? = null
    private val selector = SelectorManager(Dispatchers.IO)
    private val logger = object : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = Unit
    }
    private val random = SecureRandom()

    @AfterTest
    fun stopServer() {
        server?.destroy()
        server?.waitFor()
        selector.close()
    }

    /** Arranca el servidor y devuelve su perfil de cliente, o null si no se puede en este equipo. */
    private fun startServer(): OvpnConfig? {
        if (openvpn == null || script == null) {
            println("OpenVpnLocalServerTest: sin openvpn instalado, no se comprueba contra el servidor de referencia")
            return null
        }
        // Con el equipo cargado el arranque a veces se retrasa o el puerto elegido ya no está libre: se reintenta.
        var lastError: Exception? = null
        repeat(3) {
            try {
                return launchServer(openvpn, script)
            } catch (e: IllegalStateException) {
                lastError = e
                server?.destroy()
            }
        }
        throw lastError ?: IllegalStateException("El servidor de prueba no arrancó")
    }

    private fun launchServer(openvpn: File, script: File): OvpnConfig {
        val port = ServerSocket(0).use { it.localPort }
        val dir = Files.createTempDirectory("ovpn-test").toFile()
        val log = File(dir, "server.log")
        val process = ProcessBuilder("bash", script.path, dir.path, port.toString(), "3")
            .redirectErrorStream(true).redirectOutput(log)
            .also { it.environment()["OPENVPN"] = openvpn.path }
            .start()
        server = process
        val deadline = System.currentTimeMillis() + 30_000
        while (!(log.exists() && log.readText().contains("Initialization Sequence Completed"))) {
            check(process.isAlive && System.currentTimeMillis() < deadline) { "El servidor de prueba no arrancó: ${log.readText().takeLast(1500)}" }
            Thread.sleep(100)
        }
        return parseOvpn(File(dir, "client.ovpn").readText())
    }

    private fun client() = OpenVpnClient(
        crypto = JcaOpenVpnCrypto(),
        tls = SslEngineTlsSessionFactory(),
        random = RandomBytes { ByteArray(it).also(random::nextBytes) },
        logger = logger,
        nowSeconds = { System.currentTimeMillis() / 1000 },
    )

    private suspend fun connect(config: OvpnConfig, password: String): OpenVpnSession {
        val tcp = KtorTcpConnector(selector, AppDispatchers(background = Dispatchers.IO))
        val remote = config.remotes.first()
        return client().connect(config, tcp.connect(remote.host, remote.port), "tester", password)
    }

    @Test
    fun `a session is established and the server's configuration is understood`() = runBlocking {
        val config = startServer() ?: return@runBlocking

        val session = connect(config, "secreta")

        assertEquals("10.77.0.2", session.push.address)
        assertEquals("255.255.255.0", session.push.netmask)
        assertEquals(listOf("10.77.0.1"), session.push.dnsServers)
        assertEquals("AES-256-GCM", session.push.cipher)
        session.close()
    }

    @Test
    fun `a wrong password is reported as rejected credentials`() = runBlocking {
        val config = startServer() ?: return@runBlocking

        assertFailsWith<AuthFailedException> { connect(config, "equivocada") }
        Unit
    }

    @Test
    fun `the server accepts data packets and keeps the session open`() = runBlocking {
        val config = startServer() ?: return@runBlocking
        val session = connect(config, "secreta")

        // Un paquete IP cualquiera: el servidor lo descifra y lo descarta (no hay interfaz). Si no lograra autenticarlo,
        // cerraría la conexión, como hace con cualquier paquete que no se autentica.
        repeat(5) { session.writePacket(ByteArray(60) { 0x45 }) }
        val closed = withTimeoutOrNull(1_500) {
            session.receive { }
            true
        }

        assertTrue(closed == null, "el servidor cerró la sesión: no autenticó los paquetes de datos")
        session.close()
    }

    @Test
    fun `a profile whose authority does not match the server is refused`() = runBlocking {
        val config = startServer() ?: return@runBlocking
        val other = parseOvpn(
            // Otra autoridad cualquiera: el servidor de prueba no está firmado por ella.
            """
            client
            remote ${config.remotes.first().host} ${config.remotes.first().port}
            proto tcp
            auth-user-pass
            <ca>
            ${otherAuthority()}
            </ca>
            <tls-crypt>
            ${keyBlock(config)}
            </tls-crypt>
            """.trimIndent(),
        )

        assertFailsWith<OpenVpnException> { connect(other, "secreta") }
        Unit
    }

    private fun keyBlock(config: OvpnConfig): String {
        val hex = config.tlsCryptKey.joinToString("") { "%02x".format(it) }
        return "-----BEGIN OpenVPN Static key V1-----\n" + hex.chunked(32).joinToString("\n") + "\n-----END OpenVPN Static key V1-----"
    }

    private fun otherAuthority(): String {
        val dir = Files.createTempDirectory("other-ca").toFile()
        ProcessBuilder(
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", File(dir, "k").path,
            "-out", File(dir, "c").path, "-days", "1", "-subj", "/CN=Otra",
        ).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        return File(dir, "c").readText().trim()
    }
}
