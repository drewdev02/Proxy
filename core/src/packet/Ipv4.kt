package org.uci.proxy.core.packet

/*
 * Lectura y construcción de paquetes IPv4 y UDP. Los campos de red van en big-endian.
 * Port de la parte de paquetes de PacketUtils.cs de SuperProxyWin.
 */

const val PROTOCOL_TCP = 6
const val PROTOCOL_UDP = 17

private const val IPV4_MIN_HEADER = 20
private const val UDP_HEADER = 8

internal fun ByteArray.readU16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.readInt(offset: Int): Int = (readU16(offset) shl 16) or readU16(offset + 2)

internal fun ByteArray.writeU16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.writeInt(offset: Int, value: Int) {
    writeU16(offset, value ushr 16)
    writeU16(offset + 2, value)
}

/** Convierte "10.1.2.3" en su valor de 32 bits. Devuelve null si el texto no es una IPv4. */
fun parseIpv4(text: String): Int? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    var value = 0
    for (part in parts) {
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 && part.length <= 3 } ?: return null
        value = (value shl 8) or octet
    }
    return value
}

fun formatIpv4(address: Int): String =
    "${address ushr 24}.${(address ushr 16) and 0xFF}.${(address ushr 8) and 0xFF}.${address and 0xFF}"

/** Vista de solo lectura sobre un paquete IPv4 ya validado. */
class Ipv4Packet private constructor(val bytes: ByteArray, val headerLength: Int, val totalLength: Int) {
    val protocol: Int get() = bytes[9].toInt() and 0xFF
    val source: Int get() = bytes.readInt(12)
    val destination: Int get() = bytes.readInt(16)

    /** Bytes que siguen a la cabecera IP. */
    val payloadLength: Int get() = totalLength - headerLength

    companion object {
        /** Devuelve null si [bytes] no es un paquete IPv4 completo y sin fragmentar. */
        fun parse(bytes: ByteArray): Ipv4Packet? {
            if (bytes.size < IPV4_MIN_HEADER || (bytes[0].toInt() and 0xF0) != 0x40) return null
            val headerLength = (bytes[0].toInt() and 0x0F) * 4
            val totalLength = bytes.readU16(2)
            if (headerLength < IPV4_MIN_HEADER || totalLength < headerLength || totalLength > bytes.size) return null
            // Los fragmentos no se reensamblan: bit "más fragmentos" o desplazamiento distinto de cero.
            if (bytes.readU16(6) and 0x3FFF != 0) return null
            return Ipv4Packet(bytes, headerLength, totalLength)
        }
    }
}

/** Datagrama UDP dentro de un [Ipv4Packet]. */
class UdpDatagram private constructor(private val packet: Ipv4Packet) {
    val sourcePort: Int get() = packet.bytes.readU16(packet.headerLength)
    val destinationPort: Int get() = packet.bytes.readU16(packet.headerLength + 2)

    val payload: ByteArray
        get() = packet.bytes.copyOfRange(packet.headerLength + UDP_HEADER, packet.totalLength)

    companion object {
        fun parse(packet: Ipv4Packet): UdpDatagram? {
            if (packet.protocol != PROTOCOL_UDP || packet.payloadLength < UDP_HEADER) return null
            return UdpDatagram(packet)
        }
    }
}

/** Construye un paquete IPv4 + UDP completo, con ambos checksums calculados. */
fun buildUdpPacket(source: Int, destination: Int, sourcePort: Int, destinationPort: Int, payload: ByteArray): ByteArray {
    val udpLength = UDP_HEADER + payload.size
    val packet = ByteArray(IPV4_MIN_HEADER + udpLength)

    packet[0] = 0x45 // versión 4, cabecera de 5 palabras
    packet.writeU16(2, packet.size)
    packet.writeU16(6, 0x4000) // no fragmentar
    packet[8] = 64 // TTL
    packet[9] = PROTOCOL_UDP.toByte()
    packet.writeInt(12, source)
    packet.writeInt(16, destination)
    packet.writeU16(10, internetChecksum(sumWords(packet, 0, IPV4_MIN_HEADER)))

    packet.writeU16(IPV4_MIN_HEADER, sourcePort)
    packet.writeU16(IPV4_MIN_HEADER + 2, destinationPort)
    packet.writeU16(IPV4_MIN_HEADER + 4, udpLength)
    payload.copyInto(packet, IPV4_MIN_HEADER + UDP_HEADER)

    // El checksum UDP cubre una pseudocabecera con las direcciones, el protocolo y la longitud.
    val pseudoHeader = sumWords(packet, 12, 8) + PROTOCOL_UDP + udpLength
    val checksum = internetChecksum(pseudoHeader + sumWords(packet, IPV4_MIN_HEADER, udpLength))
    // En UDP el valor 0 significa "sin checksum", así que un resultado 0 se transmite como 0xFFFF.
    packet.writeU16(IPV4_MIN_HEADER + 6, if (checksum == 0) 0xFFFF else checksum)
    return packet
}

/** Suma de palabras de 16 bits de [length] bytes; un byte suelto final cuenta como byte alto. */
internal fun sumWords(bytes: ByteArray, offset: Int, length: Int): Long {
    var sum = 0L
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        sum += bytes.readU16(index)
        index += 2
    }
    if (index < end) sum += (bytes[index].toInt() and 0xFF) shl 8
    return sum
}

/** Checksum de Internet (RFC 1071): pliega los acarreos y complementa a uno. */
internal fun internetChecksum(sum: Long): Int {
    var folded = sum
    while (folded ushr 16 != 0L) folded = (folded and 0xFFFF) + (folded ushr 16)
    return folded.toInt().inv() and 0xFFFF
}
