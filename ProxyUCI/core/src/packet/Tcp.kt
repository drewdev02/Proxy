package org.uci.proxy.core.packet

/* Lectura y construcción de segmentos TCP y del aviso ICMP de puerto inalcanzable. */

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

private const val IPV4_HEADER = 20
private const val TCP_MIN_HEADER = 20
private const val ICMP_HEADER = 8
private const val PROTOCOL_ICMP = 1
private const val OPTION_END = 0
private const val OPTION_NOP = 1
private const val OPTION_MSS = 2

/** Segmento TCP dentro de un [Ipv4Packet]. Los números de secuencia se manejan como Int con desbordamiento. */
class TcpSegment private constructor(private val packet: Ipv4Packet, private val headerLength: Int) {
    private val start: Int get() = packet.headerLength

    val sourcePort: Int get() = packet.bytes.readU16(start)
    val destinationPort: Int get() = packet.bytes.readU16(start + 2)
    val sequence: Int get() = packet.bytes.readInt(start + 4)
    val acknowledgment: Int get() = packet.bytes.readInt(start + 8)
    val flags: Int get() = packet.bytes[start + 13].toInt() and 0xFF
    val window: Int get() = packet.bytes.readU16(start + 14)

    val payloadLength: Int get() = packet.totalLength - start - headerLength

    val payload: ByteArray
        get() = packet.bytes.copyOfRange(start + headerLength, packet.totalLength)

    fun has(flag: Int): Boolean = flags and flag != 0

    /** Tamaño máximo de segmento que anuncia el otro extremo en su SYN, o null si no lo indica. */
    val maxSegmentSize: Int?
        get() {
            val bytes = packet.bytes
            var position = start + TCP_MIN_HEADER
            val end = start + headerLength
            while (position < end) {
                when (val kind = bytes[position].toInt() and 0xFF) {
                    OPTION_END -> return null
                    OPTION_NOP -> position++
                    else -> {
                        if (position + 1 >= end) return null
                        val length = bytes[position + 1].toInt() and 0xFF
                        if (length < 2 || position + length > end) return null
                        if (kind == OPTION_MSS && length == 4) return bytes.readU16(position + 2)
                        position += length
                    }
                }
            }
            return null
        }

    companion object {
        fun parse(packet: Ipv4Packet): TcpSegment? {
            if (packet.protocol != PROTOCOL_TCP || packet.payloadLength < TCP_MIN_HEADER) return null
            val headerLength = ((packet.bytes[packet.headerLength + 12].toInt() and 0xF0) ushr 4) * 4
            if (headerLength < TCP_MIN_HEADER || headerLength > packet.payloadLength) return null
            return TcpSegment(packet, headerLength)
        }
    }
}

/**
 * Construye un paquete IPv4 + TCP completo, con ambos checksums.
 *
 * @param mssOption si no es null, se añade la opción de tamaño máximo de segmento (solo tiene sentido en un SYN).
 */
fun buildTcpPacket(
    source: Int,
    destination: Int,
    sourcePort: Int,
    destinationPort: Int,
    sequence: Int,
    acknowledgment: Int,
    flags: Int,
    window: Int,
    payload: ByteArray = ByteArray(0),
    mssOption: Int? = null,
): ByteArray {
    val tcpHeader = if (mssOption != null) TCP_MIN_HEADER + 4 else TCP_MIN_HEADER
    val tcpLength = tcpHeader + payload.size
    val packet = ByteArray(IPV4_HEADER + tcpLength)

    packet[0] = 0x45
    packet.writeU16(2, packet.size)
    packet.writeU16(6, 0x4000) // no fragmentar
    packet[8] = 64 // TTL
    packet[9] = PROTOCOL_TCP.toByte()
    packet.writeInt(12, source)
    packet.writeInt(16, destination)
    packet.writeU16(10, internetChecksum(sumWords(packet, 0, IPV4_HEADER)))

    packet.writeU16(IPV4_HEADER, sourcePort)
    packet.writeU16(IPV4_HEADER + 2, destinationPort)
    packet.writeInt(IPV4_HEADER + 4, sequence)
    packet.writeInt(IPV4_HEADER + 8, acknowledgment)
    packet[IPV4_HEADER + 12] = ((tcpHeader / 4) shl 4).toByte()
    packet[IPV4_HEADER + 13] = flags.toByte()
    packet.writeU16(IPV4_HEADER + 14, window)
    if (mssOption != null) {
        packet[IPV4_HEADER + 20] = OPTION_MSS.toByte()
        packet[IPV4_HEADER + 21] = 4
        packet.writeU16(IPV4_HEADER + 22, mssOption)
    }
    payload.copyInto(packet, IPV4_HEADER + tcpHeader)

    val pseudoHeader = sumWords(packet, 12, 8) + PROTOCOL_TCP + tcpLength
    packet.writeU16(IPV4_HEADER + 16, internetChecksum(pseudoHeader + sumWords(packet, IPV4_HEADER, tcpLength)))
    return packet
}

/**
 * Respuesta ICMP "destino inalcanzable, puerto inalcanzable" al paquete [original].
 * Con ella un cliente UDP (QUIC, por ejemplo) falla al instante y recurre a TCP en vez de esperar.
 *
 * @return null si el destino es de difusión o multidifusión, a los que no se responde.
 */
fun buildIcmpPortUnreachable(original: Ipv4Packet): ByteArray? {
    val destination = original.destination
    if (destination ushr 28 >= 0xE || destination == 0) return null

    // ICMP devuelve la cabecera IP del paquete rechazado y sus primeros 8 bytes.
    val quoted = minOf(original.totalLength, original.headerLength + 8)
    val packet = ByteArray(IPV4_HEADER + ICMP_HEADER + quoted)

    packet[0] = 0x45
    packet.writeU16(2, packet.size)
    packet.writeU16(6, 0x4000)
    packet[8] = 64
    packet[9] = PROTOCOL_ICMP.toByte()
    packet.writeInt(12, destination)
    packet.writeInt(16, original.source)
    packet.writeU16(10, internetChecksum(sumWords(packet, 0, IPV4_HEADER)))

    packet[IPV4_HEADER] = 3 // tipo: destino inalcanzable
    packet[IPV4_HEADER + 1] = 3 // código: puerto inalcanzable
    original.bytes.copyInto(packet, IPV4_HEADER + ICMP_HEADER, 0, quoted)
    packet.writeU16(IPV4_HEADER + 2, internetChecksum(sumWords(packet, IPV4_HEADER, ICMP_HEADER + quoted)))
    return packet
}
