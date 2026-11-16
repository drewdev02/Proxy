package org.uci.proxy.core.tcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.PacketWriter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hace de app al otro lado del túnel: construye los segmentos que enviaría el sistema operativo
 * y recoge lo que la pila le devuelve. El tiempo es el virtual de la prueba.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpTestClient(
    private val scope: TestScope,
    val serverIp: Int = parseIpv4("93.184.216.34")!!,
    val serverPort: Int = 443,
    onAccept: suspend (TunTcpConnection) -> Unit = {},
) {
    val clientIp = parseIpv4("10.57.0.1")!!
    val clientPort = 40000

    /** Segmentos que la pila envió a la app y que la prueba aún no ha consumido. */
    private val inbox = ArrayDeque<TcpSegment>()
    val accepted = mutableListOf<TunTcpConnection>()

    val stack = TunTcpStack(
        output = PacketWriter { bytes -> inbox.addLast(TcpSegment.parse(Ipv4Packet.parse(bytes)!!)!!) },
        scope = scope.backgroundScope,
        nowMs = { scope.testScheduler.currentTime },
        randomBytes = { ByteArray(it) { 1 } },
        onAccept = { connection ->
            accepted += connection
            onAccept(connection)
        },
    ).also { it.start() }

    /** Siguiente número de secuencia que enviará la app. */
    var sequence = 1000

    /** Siguiente número de secuencia que la app espera de la pila. */
    var expected = 0

    suspend fun send(
        flags: Int,
        payload: ByteArray = ByteArray(0),
        sequence: Int = this.sequence,
        acknowledgment: Int = expected,
        window: Int = 65_535,
        mss: Int? = null,
        destinationHost: String? = null,
    ) {
        val bytes = buildTcpPacket(clientIp, serverIp, clientPort, serverPort, sequence, acknowledgment, flags, window, payload, mss)
        val packet = Ipv4Packet.parse(bytes)!!
        stack.process(packet, TcpSegment.parse(packet)!!, destinationHost)
        scope.runCurrent()
    }

    /** Siguiente segmento recibido de la pila. Falla si no hay ninguno. */
    fun next(): TcpSegment = assertNotNull(inbox.removeFirstOrNull(), "la pila no envió nada")

    fun pending(): Int = inbox.size

    fun drain(): List<TcpSegment> = inbox.toList().also { inbox.clear() }

    /** Completa el saludo de tres pasos y devuelve la conexión entregada a quien acepta. */
    suspend fun handshake(destinationHost: String? = null, window: Int = 65_535): TunTcpConnection {
        send(TcpFlags.SYN, acknowledgment = 0, mss = 1460, window = window, destinationHost = destinationHost)
        val synAck = next()
        assertTrue(synAck.has(TcpFlags.SYN) && synAck.has(TcpFlags.ACK), "se esperaba SYN-ACK")
        assertEquals(sequence + 1, synAck.acknowledgment)
        sequence += 1
        expected = synAck.sequence + 1

        send(TcpFlags.ACK, window = window)
        return accepted.last()
    }

    /** La app envía datos y avanza su número de secuencia. */
    suspend fun sendData(text: String, flags: Int = TcpFlags.ACK or TcpFlags.PSH) {
        val payload = text.encodeToByteArray()
        send(flags, payload)
        sequence += payload.size
    }

    /** Confirma todo lo recibido hasta [upTo]. */
    suspend fun acknowledge(upTo: Int, window: Int = 65_535) {
        expected = upTo
        send(TcpFlags.ACK, window = window)
    }
}

internal fun TcpSegment.text(): String = payload.decodeToString()
