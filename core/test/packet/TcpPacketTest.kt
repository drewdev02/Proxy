package org.uci.proxy.core.packet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcpPacketTest {
    private val client = parseIpv4("10.57.0.1")!!
    private val server = parseIpv4("93.184.216.34")!!

    private fun segmentOf(bytes: ByteArray) = assertNotNull(TcpSegment.parse(assertNotNull(Ipv4Packet.parse(bytes))))

    @Test
    fun `a built segment parses back to the same fields`() {
        val payload = "GET / HTTP/1.1".encodeToByteArray()

        val bytes = buildTcpPacket(client, server, 40000, 443, sequence = -5, acknowledgment = 77, TcpFlags.ACK or TcpFlags.PSH, 4096, payload)
        val packet = assertNotNull(Ipv4Packet.parse(bytes))
        val segment = segmentOf(bytes)

        assertEquals(client, packet.source)
        assertEquals(server, packet.destination)
        assertEquals(40000, segment.sourcePort)
        assertEquals(443, segment.destinationPort)
        assertEquals(-5, segment.sequence, "los números de secuencia altos se conservan")
        assertEquals(77, segment.acknowledgment)
        assertTrue(segment.has(TcpFlags.ACK) && segment.has(TcpFlags.PSH))
        assertFalse(segment.has(TcpFlags.SYN))
        assertEquals(4096, segment.window)
        assertContentEquals(payload, segment.payload)
        assertNull(segment.maxSegmentSize)
    }

    @Test
    fun `a built segment has valid IP and TCP checksums`() {
        val bytes = buildTcpPacket(client, server, 40000, 443, 1, 2, TcpFlags.ACK, 100, "abc".encodeToByteArray())
        val tcpLength = bytes.size - 20

        assertEquals(0, internetChecksum(sumWords(bytes, 0, 20)))
        val pseudoHeader = sumWords(bytes, 12, 8) + PROTOCOL_TCP + tcpLength
        assertEquals(0, internetChecksum(pseudoHeader + sumWords(bytes, 20, tcpLength)))
    }

    @Test
    fun `the MSS option is written and read back`() {
        val segment = segmentOf(buildTcpPacket(client, server, 1, 2, 0, 0, TcpFlags.SYN, 100, mssOption = 1360))

        assertEquals(1360, segment.maxSegmentSize)
        assertEquals(0, segment.payloadLength)
    }

    @Test
    fun `the MSS is found after other options`() {
        // Cabecera de 32 bytes: NOP, NOP, escala de ventana (3 bytes), NOP, MSS 1400, y relleno.
        val bytes = buildTcpPacket(client, server, 1, 2, 0, 0, TcpFlags.SYN, 100, payload = ByteArray(12))
        bytes[20 + 12] = (8 shl 4).toByte()
        byteArrayOf(1, 1, 3, 3, 7, 1, 2, 4, 0x05, 0x78, 0, 0).copyInto(bytes, 40)

        assertEquals(1400, segmentOf(bytes).maxSegmentSize)
    }

    @Test
    fun `segments with an impossible header length are rejected`() {
        val bytes = buildTcpPacket(client, server, 1, 2, 0, 0, TcpFlags.ACK, 100)
        val packet = { modified: ByteArray -> assertNotNull(Ipv4Packet.parse(modified)) }

        assertNull(TcpSegment.parse(packet(bytes.copyOf().also { it[32] = (4 shl 4).toByte() })), "menor que 20 bytes")
        assertNull(TcpSegment.parse(packet(bytes.copyOf().also { it[32] = (15 shl 4).toByte() })), "mayor que el paquete")
        assertNull(TcpSegment.parse(packet(buildUdpPacket(1, 2, 3, 4, ByteArray(30)))), "no es TCP")
    }

    @Test
    fun `ICMP port unreachable quotes the rejected packet and swaps the addresses`() {
        val original = assertNotNull(Ipv4Packet.parse(buildUdpPacket(client, server, 50000, 443, ByteArray(100))))

        val reply = assertNotNull(buildIcmpPortUnreachable(original))
        val parsed = assertNotNull(Ipv4Packet.parse(reply))

        assertEquals(1, parsed.protocol, "ICMP")
        assertEquals(server, parsed.source)
        assertEquals(client, parsed.destination)
        assertEquals(3, reply[20].toInt(), "tipo: destino inalcanzable")
        assertEquals(3, reply[21].toInt(), "código: puerto inalcanzable")
        // Cita la cabecera IP y los 8 primeros bytes del datagrama rechazado.
        assertContentEquals(original.bytes.copyOf(28), reply.copyOfRange(28, 56))
        assertEquals(56, reply.size)
        assertEquals(0, internetChecksum(sumWords(reply, 0, 20)))
        assertEquals(0, internetChecksum(sumWords(reply, 20, reply.size - 20)))
    }

    @Test
    fun `multicast and broadcast destinations get no ICMP reply`() {
        fun replyTo(destination: String) =
            buildIcmpPortUnreachable(Ipv4Packet.parse(buildUdpPacket(client, parseIpv4(destination)!!, 1, 2, ByteArray(8)))!!)

        assertNull(replyTo("224.0.0.251"))
        assertNull(replyTo("255.255.255.255"))
    }
}

class HostSnifferTest {
    /** ClientHello mínimo con la extensión server_name, precedido de otra extensión cualquiera. */
    private fun clientHello(serverName: String?, sessionId: Int = 32): ByteArray {
        val name = serverName?.encodeToByteArray()
        val sni = if (name == null) {
            ByteArray(0)
        } else {
            byteArrayOf(0, 0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        }
        val otherExtension = byteArrayOf(0, 0x17, 0, 0) // extended_master_secret, vacía
        val extensions = otherExtension + sni

        val body = byteArrayOf(3, 3) + ByteArray(32) { 7 } + // versión y aleatorio
            byteArrayOf(sessionId.toByte()) + ByteArray(sessionId) +
            u16(4) + byteArrayOf(0x13, 0x01, 0x13, 0x02) + // suites de cifrado
            byteArrayOf(1, 0) + // métodos de compresión
            u16(extensions.size) + extensions
        val handshake = byteArrayOf(1, 0) + u16(body.size) + body
        return byteArrayOf(22, 3, 1) + u16(handshake.size) + handshake
    }

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    @Test
    fun `the server name of a TLS ClientHello is extracted`() {
        assertEquals("www.uci.cu", sniffHostname(clientHello("www.uci.cu")))
    }

    @Test
    fun `the session id length is honoured`() {
        assertEquals("example.org", sniffHostname(clientHello("example.org", sessionId = 0)))
    }

    @Test
    fun `a ClientHello without server name gives nothing`() {
        assertNull(sniffHostname(clientHello(serverName = null)))
    }

    @Test
    fun `a truncated ClientHello gives nothing instead of failing`() {
        val hello = clientHello("www.uci.cu")

        for (length in 0 until hello.size) assertNull(sniffHostname(hello.copyOf(length)), "longitud $length")
    }

    @Test
    fun `the Host header of an HTTP request is extracted without the port`() {
        val request = "GET /index.html HTTP/1.1\r\nUser-Agent: x\r\nHost: intranet.uci.cu:8080\r\n\r\n"

        assertEquals("intranet.uci.cu", sniffHostname(request.encodeToByteArray()))
    }

    @Test
    fun `the Host header is found regardless of case`() {
        assertEquals("uci.cu", sniffHostname("POST / HTTP/1.1\r\nhost: uci.cu\r\n\r\n".encodeToByteArray()))
    }

    @Test
    fun `an HTTP request without Host gives nothing`() {
        assertNull(sniffHostname("GET / HTTP/1.0\r\nAccept: */*\r\n\r\n".encodeToByteArray()))
    }

    @Test
    fun `other protocols give nothing`() {
        assertNull(sniffHostname("SSH-2.0-OpenSSH_9.6\r\n".encodeToByteArray()))
        assertNull(sniffHostname(ByteArray(64)))
        assertNull(sniffHostname(ByteArray(3)))
    }
}

class RoutesTest {
    private val routes = routesExcluding(NON_PUBLIC_RANGES)

    private fun covered(address: String) = routes.any { it.contains(parseIpv4(address)!!) }

    @Test
    fun `public addresses are routed`() {
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "149.154.167.50", "172.15.255.255", "172.32.0.0", "223.255.255.255")
            .forEach { assertTrue(covered(it), it) }
    }

    @Test
    fun `private and reserved addresses are not routed`() {
        listOf(
            "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "127.0.0.1", "169.254.1.1",
            "172.16.0.1", "172.31.255.255", "192.168.1.1", "192.0.2.1", "224.0.0.251", "255.255.255.255",
        ).forEach { assertFalse(covered(it), it) }
    }

    @Test
    fun `routes do not overlap each other`() {
        for (a in routes) for (b in routes) if (a !== b) assertFalse(a.overlaps(b), "$a y $b")
    }

    @Test
    fun `routes plus exclusions cover the whole address space exactly once`() {
        fun size(route: Ipv4Route) = 1L shl (32 - route.prefixLength)

        assertEquals(1L shl 32, routes.sumOf(::size) + NON_PUBLIC_RANGES.sumOf(::size))
    }

    @Test
    fun `excluding nothing is a single default route`() {
        assertEquals(listOf(Ipv4Route(0, 0)), routesExcluding(emptyList()))
    }

    @Test
    fun `excluding from a given set only splits what overlaps and never grows it`() {
        val from = listOf(Ipv4Route.parse("192.0.2.0/24"), Ipv4Route.parse("198.51.100.0/24"))

        val result = routesExcluding(listOf(Ipv4Route.parse("192.0.2.200/32")), from = from)

        assertTrue(Ipv4Route.parse("198.51.100.0/24") in result, "lo que no se solapa queda igual")
        assertFalse(result.any { it.contains(parseIpv4("192.0.2.200")!!) })
        assertTrue(result.any { it.contains(parseIpv4("192.0.2.77")!!) })
        assertFalse(result.any { it.contains(parseIpv4("8.8.8.8")!!) }, "nada fuera de lo dado")
        fun size(route: Ipv4Route) = 1L shl (32 - route.prefixLength)
        assertEquals(256L + 255L, result.sumOf(::size))
    }

    @Test
    fun `a route knows what it contains and covers`() {
        val ten = Ipv4Route.parse("10.0.0.0/8")

        assertTrue(ten.contains(parseIpv4("10.200.3.4")!!))
        assertFalse(ten.contains(parseIpv4("11.0.0.0")!!))
        assertTrue(ten.covers(Ipv4Route.parse("10.57.0.0/16")))
        assertFalse(Ipv4Route.parse("10.57.0.0/16").covers(ten))
        assertEquals("10.0.0.0/8", ten.toString())
    }
}
