package org.uci.proxy.core.vpn

import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpConnection

internal object Opcode {
    const val CONTROL_HARD_RESET_CLIENT_V2 = 7
    const val CONTROL_HARD_RESET_SERVER_V2 = 8
    const val CONTROL_SOFT_RESET_V1 = 3
    const val CONTROL_V1 = 4
    const val ACK_V1 = 5
    const val DATA_V1 = 6
    const val DATA_V2 = 9

    fun isData(opcode: Int) = opcode == DATA_V1 || opcode == DATA_V2
}

internal fun ByteArray.u32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

internal fun u32Bytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

internal fun u16Bytes(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

/** OpenVPN sobre TCP antepone a cada paquete su longitud en dos bytes. */
internal class FramedConnection(private val connection: TcpConnection) {
    private var buffer = ByteArray(0)

    /** El siguiente paquete, o null si el servidor cerró la conexión. */
    suspend fun readFrame(): ByteArray? {
        while (true) {
            if (buffer.size >= 2) {
                val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                if (buffer.size >= 2 + length) {
                    val frame = buffer.copyOfRange(2, 2 + length)
                    buffer = buffer.copyOfRange(2 + length, buffer.size)
                    return frame
                }
            }
            buffer += connection.read() ?: return null
        }
    }

    suspend fun writeFrame(packet: ByteArray) = connection.write(u16Bytes(packet.size) + packet)

    fun close() = connection.close()
}

/**
 * Envoltura `tls-crypt`: cifra y autentica los paquetes del canal de control con una clave estática, para que quien
 * mire la red (o un proxy con inspección) no vea ni un handshake TLS reconocible.
 *
 * Formato: cabecera (opcode y sesión, 9 bytes) | contador (8) | etiqueta HMAC-SHA256 (32) | contenido cifrado.
 * La etiqueta cubre cabecera, contador y contenido en claro, y sus primeros 16 bytes son el vector de AES-256-CTR.
 */
internal class TlsCrypt(private val crypto: OpenVpnCrypto, staticKey: ByteArray) {
    // La clave estática son dos bloques de 128 bytes: el cliente cifra con el segundo y descifra con el primero.
    private val sendCipherKey = staticKey.copyOfRange(128, 160)
    private val sendHmacKey = staticKey.copyOfRange(192, 224)
    private val receiveCipherKey = staticKey.copyOfRange(0, 32)
    private val receiveHmacKey = staticKey.copyOfRange(64, 96)

    fun wrap(header: ByteArray, counter: ByteArray, plaintext: ByteArray): ByteArray {
        val tag = crypto.hmac(HmacAlgorithm.SHA256, sendHmacKey, header, counter, plaintext)
        val encrypted = crypto.aesCtr(sendCipherKey, tag.copyOfRange(0, 16), plaintext)
        return header + counter + tag + encrypted
    }

    /** @return cabecera y contenido en claro, o null si no se autentica. */
    fun unwrap(wire: ByteArray): Pair<ByteArray, ByteArray>? {
        if (wire.size < HEADER + COUNTER + TAG) return null
        val header = wire.copyOfRange(0, HEADER)
        val counter = wire.copyOfRange(HEADER, HEADER + COUNTER)
        val tag = wire.copyOfRange(HEADER + COUNTER, HEADER + COUNTER + TAG)
        val encrypted = wire.copyOfRange(HEADER + COUNTER + TAG, wire.size)
        val plaintext = crypto.aesCtr(receiveCipherKey, tag.copyOfRange(0, 16), encrypted)
        val expected = crypto.hmac(HmacAlgorithm.SHA256, receiveHmacKey, header, counter, plaintext)
        return if (expected.contentEquals(tag)) header to plaintext else null
    }

    private companion object {
        const val HEADER = 9
        const val COUNTER = 8
        const val TAG = 32
    }
}

internal sealed interface Received {
    /** [packetId] es -1 en los acuses, que no llevan. */
    class Control(val opcode: Int, val packetId: Int, val payload: ByteArray) : Received

    class Data(val wire: ByteArray) : Received
}

/** El canal de control: paquetes numerados con acuse de recibo, envueltos con `tls-crypt`. */
internal class ControlChannel(
    private val framed: FramedConnection,
    private val tlsCrypt: TlsCrypt,
    random: RandomBytes,
    private val nowSeconds: () -> Long,
) {
    val localSessionId: ByteArray = random.next(8)
    var remoteSessionId: ByteArray? = null
        private set

    private var nextPacketId = 0
    private var nextCounter = 1

    suspend fun send(opcode: Int, payload: ByteArray) {
        val plain = byteArrayOf(0) + u32Bytes(nextPacketId++) + payload
        write(opcode, plain)
    }

    suspend fun sendAck(packetId: Int) {
        val remote = remoteSessionId ?: return
        write(Opcode.ACK_V1, byteArrayOf(1) + u32Bytes(packetId) + remote)
    }

    private suspend fun write(opcode: Int, plain: ByteArray) {
        val header = byteArrayOf((opcode shl 3).toByte()) + localSessionId
        val counter = u32Bytes(nextCounter++) + u32Bytes(nowSeconds().toInt())
        framed.writeFrame(tlsCrypt.wrap(header, counter, plain))
    }

    /** El siguiente paquete válido, o null si el servidor cerró. Los que no se autentican se descartan. */
    suspend fun receive(): Received? {
        while (true) {
            val frame = framed.readFrame() ?: return null
            if (frame.isEmpty()) continue
            val opcode = (frame[0].toInt() and 0xFF) ushr 3
            if (Opcode.isData(opcode)) return Received.Data(frame)

            val (header, plain) = tlsCrypt.unwrap(frame) ?: continue
            if (remoteSessionId == null) remoteSessionId = header.copyOfRange(1, 9)
            if (plain.isEmpty()) continue

            val ackCount = plain[0].toInt() and 0xFF
            var index = 1 + 4 * ackCount + if (ackCount > 0) 8 else 0
            if (opcode == Opcode.ACK_V1) return Received.Control(opcode, -1, ByteArray(0))
            if (plain.size < index + 4) continue
            val packetId = plain.u32(index)
            index += 4
            return Received.Control(opcode, packetId, plain.copyOfRange(index, plain.size))
        }
    }
}

/** El canal de datos: cada paquete IP va cifrado con AES-GCM y numerado. */
internal class DataChannel(
    private val crypto: OpenVpnCrypto,
    private val keys: DataChannelKeys,
    private val peerId: Int,
) {
    private var sendPacketId = 1

    fun seal(plain: ByteArray): ByteArray {
        val packetId = u32Bytes(sendPacketId++)
        val header = byteArrayOf(((Opcode.DATA_V2 shl 3) or KEY_ID).toByte()) +
            byteArrayOf((peerId ushr 16).toByte(), (peerId ushr 8).toByte(), peerId.toByte())
        val sealed = crypto.aesGcmSeal(keys.send.cipherKey, packetId + keys.send.implicitIv, header + packetId, plain)
        return header + packetId + sealed.tag + sealed.ciphertext
    }

    /** @return el paquete IP (o un mensaje de mantenimiento), o null si no se autentica. */
    fun open(wire: ByteArray): ByteArray? {
        if (wire.size < HEADER + PACKET_ID + TAG) return null
        val aad = wire.copyOfRange(0, HEADER + PACKET_ID)
        val packetId = wire.copyOfRange(HEADER, HEADER + PACKET_ID)
        val tag = wire.copyOfRange(HEADER + PACKET_ID, HEADER + PACKET_ID + TAG)
        val ciphertext = wire.copyOfRange(HEADER + PACKET_ID + TAG, wire.size)
        return crypto.aesGcmOpen(keys.receive.cipherKey, packetId + keys.receive.implicitIv, aad, ciphertext, tag)
    }

    private companion object {
        const val KEY_ID = 0
        const val HEADER = 4
        const val PACKET_ID = 4
        const val TAG = 16
    }
}
