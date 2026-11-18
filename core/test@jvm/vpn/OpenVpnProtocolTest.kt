package org.uci.proxy.core.vpn

import org.uci.proxy.core.port.TcpConnection
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenVpnProtocolTest {
    private val crypto = JcaOpenVpnCrypto()
    private val random = SecureRandom()
    private fun bytes(count: Int) = ByteArray(count).also(random::nextBytes)

    /** La clave estática vista por el servidor: los dos bloques intercambiados. */
    private fun serverView(key: ByteArray) = key.copyOfRange(128, 256) + key.copyOfRange(0, 128)

    @Test
    fun `tls-crypt wraps what the other side can unwrap`() {
        val key = bytes(256)
        val client = TlsCrypt(crypto, key)
        val server = TlsCrypt(crypto, serverView(key))
        val header = byteArrayOf(0x38) + bytes(8)
        val counter = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 7)
        val plain = "contenido del canal de control".encodeToByteArray()

        val wire = client.wrap(header, counter, plain)
        val (unwrappedHeader, unwrapped) = assertNotNull(server.unwrap(wire))

        assertContentEquals(header, unwrappedHeader)
        assertContentEquals(plain, unwrapped)
        assertFalse(wire.decodeToString().contains("contenido"), "va cifrado")
    }

    @Test
    fun `tls-crypt rejects tampering and a wrong key`() {
        val key = bytes(256)
        val client = TlsCrypt(crypto, key)
        val server = TlsCrypt(crypto, serverView(key))
        val wire = client.wrap(byteArrayOf(0x38) + bytes(8), ByteArray(8), "hola".encodeToByteArray())

        val tampered = wire.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(server.unwrap(tampered))
        assertNull(TlsCrypt(crypto, serverView(bytes(256))).unwrap(wire))
        assertNull(server.unwrap(wire.copyOf(10)), "demasiado corto")
    }

    private fun keysPair(): Pair<DataChannelKeys, DataChannelKeys> {
        val a = DataKeys(bytes(32), bytes(8))
        val b = DataKeys(bytes(32), bytes(8))
        return DataChannelKeys(send = a, receive = b) to DataChannelKeys(send = b, receive = a)
    }

    @Test
    fun `data channel packets round trip in both directions`() {
        val (clientKeys, serverKeys) = keysPair()
        val client = DataChannel(crypto, clientKeys, peerId = 0x010203)
        val server = DataChannel(crypto, serverKeys, peerId = 0x010203)
        val packet = bytes(120)

        val wire = client.seal(packet)

        assertEquals(0x48, wire[0].toInt(), "opcode datos v2, clave 0")
        assertContentEquals(byteArrayOf(1, 2, 3), wire.copyOfRange(1, 4), "peer-id")
        assertEquals(4 + 4 + 16 + packet.size, wire.size)
        assertContentEquals(packet, server.open(wire))
        assertContentEquals(packet, client.open(server.seal(packet)))
    }

    @Test
    fun `each data packet gets the next packet id`() {
        val (clientKeys, _) = keysPair()
        val client = DataChannel(crypto, clientKeys, peerId = 1)

        val first = client.seal(bytes(10))
        val second = client.seal(bytes(10))

        assertEquals(1, first.u32(4))
        assertEquals(2, second.u32(4))
    }

    @Test
    fun `data channel rejects tampered or foreign packets`() {
        val (clientKeys, serverKeys) = keysPair()
        val client = DataChannel(crypto, clientKeys, peerId = 5)
        val server = DataChannel(crypto, serverKeys, peerId = 5)
        val wire = client.seal(bytes(50))

        assertNull(server.open(wire.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }), "cuerpo alterado")
        assertNull(server.open(wire.copyOf().also { it[2] = 9 }), "cabecera alterada")
        assertNull(server.open(wire.copyOf().also { it[6] = 9 }), "contador alterado")
        assertNull(DataChannel(crypto, keysPair().second, 5).open(wire), "otra clave")
        assertNull(server.open(wire.copyOf(10)), "demasiado corto")
    }

    @Test
    fun `key derivation is deterministic and depends on every input`() {
        fun derive(preMaster: ByteArray, r1: ByteArray, sid: ByteArray) =
            deriveDataKeys(crypto, preMaster, r1, ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 }, sid, ByteArray(8) { 6 }, 32)

        val base = derive(ByteArray(48) { 1 }, ByteArray(32) { 9 }, ByteArray(8) { 5 })
        val same = derive(ByteArray(48) { 1 }, ByteArray(32) { 9 }, ByteArray(8) { 5 })

        assertContentEquals(base.send.cipherKey, same.send.cipherKey)
        assertContentEquals(base.receive.implicitIv, same.receive.implicitIv)
        assertFalse(base.send.cipherKey.contentEquals(base.receive.cipherKey), "cada sentido tiene su clave")
        assertFalse(base.send.cipherKey.contentEquals(derive(ByteArray(48) { 7 }, ByteArray(32) { 9 }, ByteArray(8) { 5 }).send.cipherKey))
        assertFalse(base.send.cipherKey.contentEquals(derive(ByteArray(48) { 1 }, ByteArray(32) { 8 }, ByteArray(8) { 5 }).send.cipherKey))
        assertFalse(base.send.cipherKey.contentEquals(derive(ByteArray(48) { 1 }, ByteArray(32) { 9 }, ByteArray(8) { 4 }).send.cipherKey))
    }

    @Test
    fun `the pseudo random function produces any requested length from the same prefix`() {
        val secret = ByteArray(48) { 1 }
        val seed = ByteArray(64) { 2 }

        val short = tls1Prf(crypto, secret, "etiqueta", seed, 20)
        val long = tls1Prf(crypto, secret, "etiqueta", seed, 100)

        assertEquals(100, long.size)
        assertContentEquals(short, long.copyOf(20), "la salida es un flujo: pedir más no cambia el principio")
        assertFalse(long.contentEquals(tls1Prf(crypto, secret, "otra", seed, 100)), "la etiqueta cuenta")
    }

    @Test
    fun `framing splits and joins packets across reads`() = kotlinx.coroutines.test.runTest {
        val connection = ScriptedConnection()
        val framed = FramedConnection(connection)
        val a = bytes(300)
        val b = bytes(5)

        framed.writeFrame(a)
        framed.writeFrame(b)
        val stream = connection.written
        // Llegan en trozos que no respetan los límites de los paquetes.
        connection.deliver(stream.copyOfRange(0, 100))
        connection.deliver(stream.copyOfRange(100, 304))
        connection.deliver(stream.copyOfRange(304, stream.size))

        assertContentEquals(a, framed.readFrame())
        assertContentEquals(b, framed.readFrame())
    }

    /** Una conexión de la que se lee lo que la prueba entregue, y que recuerda todo lo escrito. */
    private class ScriptedConnection : TcpConnection {
        private val incoming = ArrayDeque<ByteArray>()
        var written = ByteArray(0)

        fun deliver(bytes: ByteArray) {
            incoming.addLast(bytes)
        }

        override suspend fun read(maxBytes: Int): ByteArray? = incoming.removeFirstOrNull()

        override suspend fun write(bytes: ByteArray) {
            written += bytes
        }

        override suspend fun shutdownOutput() = Unit

        override fun close() = Unit
    }
}
