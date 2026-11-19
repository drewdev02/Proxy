package org.uci.proxy.core.windows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.PacketWriter
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.tcp.TunTcpConnection
import org.uci.proxy.core.tcp.TunTcpStack
import org.uci.proxy.core.vpn.OpenVpnPush
import org.uci.proxy.core.vpn.VpnSession
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Una VPN de mentira en este mismo proceso, para probar las reglas de salida con el adaptador real sin credenciales: hace
 * de servidor VPN y de Internet a la vez.
 *
 * - El DNS de la VPN contesta cualquier nombre con [SERVER] (192.0.2.77, de documentación).
 * - Las conexiones TCP que llegan por la VPN las termina una [TunTcpStack] propia y las lleva al origen de pruebas en
 *   127.0.0.1:[originPort]. [accepted] cuenta cuántas llegaron: así se sabe que fueron por la VPN y no por el proxy.
 */
internal class LoopbackVpnSession(
    private val scope: CoroutineScope,
    private val originPort: Int,
    randomBytes: RandomBytes,
) : VpnSession {
    override val push = OpenVpnPush(
        address = "10.99.0.2", netmask = "255.255.255.0", dnsServers = listOf("10.99.0.1"), peerId = 0, cipher = "AES-256-GCM",
        tunMtu = 1500, pingSeconds = 10, pingRestartSeconds = 60, redirectGateway = true,
    )
    val accepted = AtomicInteger()
    val dnsQueries = AtomicInteger()

    private val toTunnel = Channel<ByteArray>(Channel.UNLIMITED)
    private val start = System.currentTimeMillis()
    private val stack = TunTcpStack(
        output = PacketWriter { toTunnel.send(it) },
        scope = scope,
        nowMs = { System.currentTimeMillis() - start },
        randomBytes = randomBytes,
        onAccept = ::serve,
    ).also { it.start() }

    override suspend fun writePacket(packet: ByteArray) {
        val ip = Ipv4Packet.parse(packet) ?: return
        val datagram = UdpDatagram.parse(ip)
        if (datagram != null) {
            if (datagram.destinationPort == DNS_PORT) answerDns(ip, datagram)
            return
        }
        TcpSegment.parse(ip)?.let { stack.process(ip, it, destinationHost = null) }
    }

    override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) {
        for (packet in toTunnel) onPacket(packet)
    }

    override fun close() {
        toTunnel.close()
    }

    private suspend fun answerDns(ip: Ipv4Packet, datagram: UdpDatagram) {
        val query = datagram.payload
        val question = DnsResponder.parseQuestionOf(query) ?: return
        dnsQueries.incrementAndGet()
        val answer = if (question.type == DnsResponder.TYPE_A) {
            byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + SERVER_BYTES
        } else {
            ByteArray(0)
        }
        val reply = query + answer
        reply[2] = 0x81.toByte()
        reply[3] = 0x80.toByte()
        reply[6] = 0
        reply[7] = if (answer.isEmpty()) 0 else 1
        toTunnel.send(buildUdpPacket(ip.destination, ip.source, DNS_PORT, datagram.sourcePort, reply))
    }

    /** Lleva una conexión que llegó por la VPN al origen de pruebas, copiando en ambos sentidos. */
    private suspend fun serve(connection: TunTcpConnection) {
        accepted.incrementAndGet()
        val socket = withContext(Dispatchers.IO) { Socket().apply { connect(InetSocketAddress("127.0.0.1", originPort), 5_000) } }
        try {
            coroutineScope {
                launch(Dispatchers.IO) {
                    val output = socket.getOutputStream()
                    while (true) output.write(connection.read() ?: break)
                    socket.shutdownOutput()
                }
                withContext(Dispatchers.IO) {
                    val input = socket.getInputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        connection.write(buffer.copyOf(count))
                    }
                }
                connection.finish()
            }
        } catch (_: Exception) {
            connection.abort()
        } finally {
            withContext(Dispatchers.IO) { socket.close() }
        }
    }

    companion object {
        const val SERVER = "192.0.2.77"
        private const val DNS_PORT = 53
        private val SERVER_BYTES = parseIpv4(SERVER)!!.let { byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte()) }
    }
}
