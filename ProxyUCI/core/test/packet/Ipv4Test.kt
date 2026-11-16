package org.uci.proxy.core.packet

import org.uci.proxy.core.auth.hexToBytes
import org.uci.proxy.core.auth.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Ipv4Test {
    @Test
    fun `addresses convert between text and number`() {
        assertEquals(0x0A010203, parseIpv4("10.1.2.3"))
        assertEquals(-1, parseIpv4("255.255.255.255"))
        assertEquals("10.1.2.3", formatIpv4(0x0A010203))
        assertEquals("255.255.255.255", formatIpv4(-1))
    }

    @Test
    fun `text that is not an IPv4 address is rejected`() {
        assertNull(parseIpv4("10.1.2"))
        assertNull(parseIpv4("10.1.2.256"))
        assertNull(parseIpv4("10.1.2.x"))
        assertNull(parseIpv4("proxy.uci.cu"))
        assertNull(parseIpv4(""))
    }

    // Cabecera de ejemplo habitual: 192.168.0.1 -> 192.168.0.199, UDP, checksum 0xb861.
    private val sampleHeader = "45000073000040004011b861c0a80001c0a800c7".hexToBytes()

    @Test
    fun `the internet checksum matches a known header`() {
        val withoutChecksum = sampleHeader.copyOf().also { it.writeU16(10, 0) }

        assertEquals(0xb861, internetChecksum(sumWords(withoutChecksum, 0, 20)))
    }

    @Test
    fun `a header with a correct checksum sums to zero`() {
        assertEquals(0, internetChecksum(sumWords(sampleHeader, 0, 20)))
    }

    @Test
    fun `an odd trailing byte counts as the high byte of a word`() {
        assertEquals(0x0100L + 0x0200L, sumWords(byteArrayOf(1, 0, 2), 0, 3))
    }

    @Test
    fun `a built UDP packet parses back to the same fields`() {
        val payload = "hola dns".encodeToByteArray()

        val packet = assertNotNull(Ipv4Packet.parse(buildUdpPacket(0x0A390002, 0x0A390001, 53, 40000, payload)))
        val datagram = assertNotNull(UdpDatagram.parse(packet))

        assertEquals(PROTOCOL_UDP, packet.protocol)
        assertEquals("10.57.0.2", formatIpv4(packet.source))
        assertEquals("10.57.0.1", formatIpv4(packet.destination))
        assertEquals(53, datagram.sourcePort)
        assertEquals(40000, datagram.destinationPort)
        assertContentEquals(payload, datagram.payload)
    }

    @Test
    fun `a built UDP packet has valid IP and UDP checksums`() {
        // Carga de longitud impar, para ejercitar el byte suelto del checksum.
        val bytes = buildUdpPacket(0x0A390002, 0x0A390001, 53, 40000, "abc".encodeToByteArray())
        val udpLength = bytes.size - 20

        assertEquals(0, internetChecksum(sumWords(bytes, 0, 20)))
        val pseudoHeader = sumWords(bytes, 12, 8) + PROTOCOL_UDP + udpLength
        assertEquals(0, internetChecksum(pseudoHeader + sumWords(bytes, 20, udpLength)))
    }

    @Test
    fun `packets that are not complete unfragmented IPv4 are rejected`() {
        val valid = buildUdpPacket(1, 2, 3, 4, ByteArray(4))

        assertNull(Ipv4Packet.parse(ByteArray(10)), "demasiado corto")
        assertNull(Ipv4Packet.parse(valid.copyOf().also { it[0] = 0x65 }), "IPv6")
        assertNull(Ipv4Packet.parse(valid.copyOf(valid.size - 1)), "truncado")
        assertNull(Ipv4Packet.parse(valid.copyOf().also { it.writeU16(6, 0x2000) }), "fragmento")
    }

    @Test
    fun `a TCP packet is not a UDP datagram`() {
        val tcp = buildUdpPacket(1, 2, 3, 4, ByteArray(20)).also { it[9] = PROTOCOL_TCP.toByte() }

        assertNull(UdpDatagram.parse(assertNotNull(Ipv4Packet.parse(tcp))))
    }

    @Test
    fun `big endian helpers round trip`() {
        val bytes = ByteArray(6)
        bytes.writeU16(0, 0xBEEF)
        bytes.writeInt(2, 0x0A0B0C0D)

        assertEquals("beef0a0b0c0d", bytes.toHex())
        assertEquals(0xBEEF, bytes.readU16(0))
        assertEquals(0x0A0B0C0D, bytes.readInt(2))
    }
}
