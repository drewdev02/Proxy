package org.uci.proxy.core.vpn

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpConnection
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.proxy.BasicAuthScheme
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.NtlmAuthScheme
import org.uci.proxy.core.proxy.ProxyConnectResult
import org.uci.proxy.core.proxy.ProxyTarget
import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prueba contra un servidor OpenVPN real. No corre sola (necesita red y credenciales): se activa con variables de entorno.
 *
 *   OVPN_FILE=/ruta/perfil.ovpn            el perfil
 *   OVPN_AUTH=/ruta/archivo                usuario en la primera línea y contraseña en la segunda
 *   OVPN_PROXY=127.0.0.1:3129              (opcional) proxy HTTP por el que llegar al servidor
 *   OVPN_PROXY_USER / OVPN_PROXY_PASSWORD  (opcional) credenciales del proxy
 */
class OpenVpnLiveTest {
    private val logger = object : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = println("[$level] $tag: $message")
    }

    @Test
    fun `connects to a real OpenVPN server and receives its configuration`() = runBlocking {
        val file = System.getenv("OVPN_FILE") ?: return@runBlocking
        val auth = File(System.getenv("OVPN_AUTH") ?: error("Falta OVPN_AUTH")).readLines()
        val config = parseOvpn(File(file).readText())
        val remote = config.remotes.first()

        val selector = SelectorManager(Dispatchers.IO)
        try {
            val random = SecureRandom()
            val randomBytes = RandomBytes { ByteArray(it).also(random::nextBytes) }
            val tcp = KtorTcpConnector(selector, AppDispatchers(background = Dispatchers.IO))
            val connection: TcpConnection = System.getenv("OVPN_PROXY")?.let { proxy ->
                val (host, port) = proxy.split(':')
                val profile = ProxyProfile("p", "proxy", host, port.toInt(), ProxyProtocol.HTTP, System.getenv("OVPN_PROXY_USER").orEmpty())
                val connector = HttpConnectConnector(tcp, listOf(NtlmAuthScheme(randomBytes, kotlin.time.Clock.System), BasicAuthScheme()))
                val result = connector.connect(profile, System.getenv("OVPN_PROXY_PASSWORD").orEmpty(), ProxyTarget(remote.host, remote.port))
                (result as? ProxyConnectResult.Connected)?.connection ?: error("El proxy no abrió el túnel: $result")
            } ?: tcp.connect(remote.host, remote.port)

            val client = OpenVpnClient(
                crypto = JcaOpenVpnCrypto(),
                tls = SslEngineTlsSessionFactory(),
                random = randomBytes,
                logger = logger,
                nowSeconds = { System.currentTimeMillis() / 1000 },
            )
            val session = client.connect(config, connection, auth[0], auth[1])
            println("PUSH: dirección=${session.push.address} máscara=${session.push.netmask} dns=${session.push.dnsServers} peer-id=${session.push.peerId} cifrado=${session.push.cipher}")

            // Un paquete ICMP de eco hacia el DNS de la VPN: si el canal de datos funciona, contesta.
            val request = dnsQuery(session.push.address, session.push.dnsServers.first())
            session.writePacket(request)
            val reply = withTimeoutOrNull(15_000) {
                var found: ByteArray? = null
                try {
                    session.receive { packet ->
                        println("RECIBIDO: paquete IP de ${packet.size} bytes, protocolo ${packet[9].toInt() and 0xFF}")
                        if (packet.size > 28 && packet[9].toInt() == 17) {
                            found = packet
                            throw StopReceiving()
                        }
                    }
                } catch (_: StopReceiving) {
                    // Llegó la respuesta.
                }
                found
            }
            session.close()
            println("ECO: ${if (reply != null) "respuesta de ${reply.size} bytes" else "sin respuesta"}")
            assertTrue(reply != null, "el servidor no contestó al eco por el canal de datos")
        } finally {
            selector.close()
        }
    }

    private class StopReceiving : Exception()

    /** Una consulta DNS de tipo A por example.com, en un datagrama UDP. */
    private fun dnsQuery(source: String, destination: String): ByteArray {
        fun ip(text: String) = text.split('.').map { it.toInt().toByte() }.toByteArray()
        val question = "example.com".split('.').flatMap { listOf(it.length.toByte()) + it.encodeToByteArray().toList() } + 0.toByte()
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) + question.toByteArray() + byteArrayOf(0, 1, 0, 1)
        val udpLength = 8 + dns.size
        val udp = byteArrayOf(0x9c.toByte(), 0x40, 0, 53, (udpLength ushr 8).toByte(), udpLength.toByte(), 0, 0) + dns
        val total = 20 + udp.size
        val header = ByteArray(20).also {
            it[0] = 0x45
            it[2] = (total ushr 8).toByte()
            it[3] = total.toByte()
            it[8] = 64
            it[9] = 17
            ip(source).copyInto(it, 12)
            ip(destination).copyInto(it, 16)
        }
        val headerChecksum = checksum(header)
        header[10] = (headerChecksum ushr 8).toByte()
        header[11] = headerChecksum.toByte()
        return header + udp
    }

    private fun icmpEcho(source: String, destination: String): ByteArray {
        fun ip(text: String) = text.split('.').map { it.toInt().toByte() }.toByteArray()
        val payload = ByteArray(32) { it.toByte() }
        val icmp = ByteArray(8 + payload.size).also {
            it[0] = 8 // eco
            it[4] = 0x12
            it[5] = 0x34
            it[7] = 1
            payload.copyInto(it, 8)
        }
        val checksum = checksum(icmp)
        icmp[2] = (checksum ushr 8).toByte()
        icmp[3] = checksum.toByte()

        val header = ByteArray(20).also {
            it[0] = 0x45
            val total = 20 + icmp.size
            it[2] = (total ushr 8).toByte()
            it[3] = total.toByte()
            it[8] = 64
            it[9] = 1 // ICMP
            ip(source).copyInto(it, 12)
            ip(destination).copyInto(it, 16)
        }
        val headerChecksum = checksum(header)
        header[10] = (headerChecksum ushr 8).toByte()
        header[11] = headerChecksum.toByte()
        return header + icmp
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
