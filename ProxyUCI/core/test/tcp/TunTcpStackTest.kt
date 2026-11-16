package org.uci.proxy.core.tcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.port.TcpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TunTcpStackTest {
    @Test
    fun `the handshake answers SYN with SYN-ACK and hands over the connection on the final ACK`() = runTest {
        val client = TcpTestClient(this)

        client.send(TcpFlags.SYN, acknowledgment = 0, mss = 1460)

        val synAck = client.next()
        assertEquals(TcpFlags.SYN or TcpFlags.ACK, synAck.flags)
        assertEquals(1001, synAck.acknowledgment)
        assertEquals(1360, synAck.maxSegmentSize)
        assertEquals(client.serverPort, synAck.sourcePort)
        assertEquals(client.clientPort, synAck.destinationPort)
        assertTrue(client.accepted.isEmpty(), "aún no está establecida")

        client.sequence = 1001
        client.expected = synAck.sequence + 1
        client.send(TcpFlags.ACK)

        val connection = client.accepted.single()
        assertEquals(client.serverIp, connection.destinationAddress)
        assertEquals(443, connection.destinationPort)
    }

    @Test
    fun `the destination host known at SYN time travels with the connection`() = runTest {
        val client = TcpTestClient(this)

        val connection = client.handshake(destinationHost = "www.uci.cu")

        assertEquals("www.uci.cu", connection.destinationHost)
    }

    @Test
    fun `a repeated SYN gets the same SYN-ACK again`() = runTest {
        val client = TcpTestClient(this)
        client.send(TcpFlags.SYN, acknowledgment = 0)
        val first = client.next()

        client.send(TcpFlags.SYN, acknowledgment = 0)

        assertEquals(first.sequence, client.next().sequence)
    }

    @Test
    fun `an unanswered SYN-ACK is retransmitted and finally reset`() = runTest {
        val client = TcpTestClient(this)
        client.send(TcpFlags.SYN, acknowledgment = 0)
        client.next()

        advanceTimeBy(700)
        assertEquals(TcpFlags.SYN or TcpFlags.ACK, client.next().flags)

        advanceTimeBy(10_000)
        assertTrue(client.drain().last().has(TcpFlags.RST))
    }

    @Test
    fun `data from the app is acknowledged and delivered to the reader`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        client.sendData("hola ")
        client.sendData("proxy")

        assertEquals(listOf(1006, 1011), client.drain().map { it.acknowledgment })
        assertEquals("hola proxy", connection.read()?.decodeToString())
    }

    @Test
    fun `a reader waits until data arrives`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        val pending = async { connection.read()?.decodeToString() }
        runCurrent()
        assertFalse(pending.isCompleted)

        client.sendData("llegó")

        assertEquals("llegó", pending.await())
    }

    @Test
    fun `read honours the requested size and keeps the rest`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        client.sendData("abcdefgh")

        assertEquals("abc", connection.read(maxBytes = 3)?.decodeToString())
        assertEquals("defgh", connection.read()?.decodeToString())
    }

    @Test
    fun `a duplicated segment is acknowledged again but not delivered twice`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        client.sendData("unico")
        client.drain()

        client.send(TcpFlags.ACK or TcpFlags.PSH, "unico".encodeToByteArray(), sequence = 1001)

        assertEquals(1006, client.next().acknowledgment)
        assertEquals("unico", connection.read()?.decodeToString())
        client.sendData("!")
        assertEquals("!", connection.read()?.decodeToString())
    }

    @Test
    fun `a retransmission that overlaps what was received contributes only the new bytes`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        client.sendData("abcd")

        client.send(TcpFlags.ACK, "cdEF".encodeToByteArray(), sequence = 1003)

        assertEquals("abcdEF", connection.read()?.decodeToString())
    }

    @Test
    fun `segments that arrive out of order are delivered in order`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        client.send(TcpFlags.ACK, "mundo".encodeToByteArray(), sequence = 1006)
        assertEquals(1001, client.next().acknowledgment, "sigue pidiendo el hueco")

        client.send(TcpFlags.ACK, "hola ".encodeToByteArray(), sequence = 1001)

        assertEquals(1011, client.next().acknowledgment)
        assertEquals("hola mundo", connection.read()?.decodeToString())
    }

    @Test
    fun `data written reaches the app split by the negotiated segment size`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        val data = ByteArray(3000) { (it % 251).toByte() }

        connection.write(data)

        val segments = client.drain()
        assertEquals(listOf(1360, 1360, 280), segments.map { it.payloadLength })
        assertEquals(data.toList(), segments.flatMap { it.payload.toList() })
        assertEquals(client.expected, segments.first().sequence)
        assertTrue(segments.all { it.has(TcpFlags.PSH) && it.acknowledgment == 1001 })
    }

    @Test
    fun `an unacknowledged segment is retransmitted until it is acknowledged`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        connection.write("dato".encodeToByteArray())
        val original = client.next()

        advanceTimeBy(500)
        val retransmitted = client.next()
        assertEquals(original.sequence, retransmitted.sequence)
        assertEquals("dato", retransmitted.text())

        client.acknowledge(original.sequence + 4)
        advanceTimeBy(5_000)

        assertEquals(0, client.pending(), "ya confirmado: no se repite más")
    }

    @Test
    fun `a segment nobody acknowledges ends in a reset`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        connection.write("dato".encodeToByteArray())

        advanceTimeBy(60_000)

        assertTrue(client.drain().last().has(TcpFlags.RST))
        assertNull(connection.read())
    }

    @Test
    fun `writing waits while the app window is closed and resumes when it reopens`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        // La app anuncia ventana cero.
        client.send(TcpFlags.ACK, window = 0)
        val writing = launch { connection.write("abcdef".encodeToByteArray()) }
        runCurrent()

        // Con la ventana cerrada solo sale un byte de sondeo.
        val probe = client.next()
        assertEquals("a", probe.text())
        assertFalse(writing.isCompleted)

        client.acknowledge(probe.sequence + 1, window = 65_535)

        assertEquals("bcdef", client.next().text())
        assertTrue(writing.isCompleted)
    }

    @Test
    fun `writing never exceeds the window the app announced`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake(window = 1000)
        val writing = launch { connection.write(ByteArray(2500)) }
        runCurrent()

        val first = client.next()
        assertEquals(1000, first.payloadLength)
        assertEquals(0, client.pending(), "espera a que se confirme antes de enviar más")

        client.acknowledge(first.sequence + 1000, window = 1000)
        val second = client.next()
        client.acknowledge(second.sequence + 1000, window = 1000)

        assertEquals(500, client.next().payloadLength)
        assertTrue(writing.isCompleted)
    }

    @Test
    fun `when the app closes its side the reader sees the end after the pending data`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        client.sendData("ultimo", flags = TcpFlags.ACK or TcpFlags.FIN)

        assertEquals(1008, client.next().acknowledgment, "datos más el FIN")
        assertEquals("ultimo", connection.read()?.decodeToString())
        assertNull(connection.read())
    }

    @Test
    fun `after the app closed its side data can still be written to it`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        client.send(TcpFlags.ACK or TcpFlags.FIN)
        client.sequence += 1
        client.drain()

        connection.write("respuesta".encodeToByteArray())

        assertEquals("respuesta", client.next().text())
    }

    @Test
    fun `finish sends FIN and the connection ends once both sides closed`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        connection.finish()
        val fin = client.next()
        assertTrue(fin.has(TcpFlags.FIN))

        // La app confirma nuestro FIN y envía el suyo.
        client.expected = fin.sequence + 1
        client.send(TcpFlags.ACK or TcpFlags.FIN)
        assertEquals(1002, client.next().acknowledgment)

        // La conexión ya no existe: un segmento tardío recibe un RST.
        client.send(TcpFlags.ACK, sequence = 1002)
        assertTrue(client.next().has(TcpFlags.RST))
    }

    @Test
    fun `finish is idempotent and writing afterwards fails`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        connection.finish()
        connection.finish()

        assertEquals(1, client.drain().count { it.has(TcpFlags.FIN) })
        assertFailsWith<TcpException> { connection.write("tarde".encodeToByteArray()) }
    }

    @Test
    fun `an unacknowledged FIN is retransmitted`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        connection.finish()
        client.next()

        advanceTimeBy(500)

        assertTrue(client.next().has(TcpFlags.FIN))
    }

    @Test
    fun `a reset from the app ends reads and makes writes fail`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()
        val pending = async { connection.read() }
        runCurrent()

        client.send(TcpFlags.RST)

        assertNull(pending.await())
        assertFailsWith<TcpException> { connection.write("x".encodeToByteArray()) }
        assertEquals(0, client.pending(), "a un RST no se responde")
    }

    @Test
    fun `abort sends a reset to the app`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        connection.abort()

        val reset = client.next()
        assertTrue(reset.has(TcpFlags.RST))
        assertEquals(client.expected, reset.sequence)
        assertNull(connection.read())
    }

    @Test
    fun `a segment for an unknown connection is answered with a reset`() = runTest {
        val client = TcpTestClient(this)

        client.send(TcpFlags.ACK, "perdido".encodeToByteArray(), sequence = 500, acknowledgment = 900)

        val reset = client.next()
        assertTrue(reset.has(TcpFlags.RST))
        assertEquals(900, reset.sequence)
    }

    @Test
    fun `sequence numbers wrap around without breaking the stream`() = runTest {
        val client = TcpTestClient(this)
        client.sequence = -3 // a tres bytes del desbordamiento de 32 bits
        val connection = client.handshake()

        client.sendData("abcdef")

        assertEquals(4, client.next().acknowledgment, "-2 + 6 bytes da la vuelta hasta 4")
        assertEquals("abcdef", connection.read()?.decodeToString())
    }

    @Test
    fun `an idle connection is eventually reset`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        advanceTimeBy(301_000)

        assertTrue(client.drain().last().has(TcpFlags.RST))
        assertNull(connection.read())
    }

    @Test
    fun `shutdown ends every connection without replying`() = runTest {
        val client = TcpTestClient(this)
        val connection = client.handshake()

        client.stack.shutdown()

        assertNull(connection.read())
        assertEquals(0, client.pending())
    }
}
