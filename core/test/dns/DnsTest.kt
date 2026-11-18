package org.uci.proxy.core.dns

import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.packet.writeU16
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Consulta DNS con una sola pregunta. */
internal fun dnsQuery(name: String, type: Int = 1, id: Int = 0x1234, flags: Int = 0x0100, questions: Int = 1): ByteArray {
    val labels = name.split('.').filter { it.isNotEmpty() }
    val encodedName = labels.flatMap { listOf(it.length.toByte()) + it.encodeToByteArray().toList() } + 0.toByte()
    val message = ByteArray(12 + encodedName.size + 4)
    message.writeU16(0, id)
    message.writeU16(2, flags)
    message.writeU16(4, questions)
    encodedName.toByteArray().copyInto(message, 12)
    message.writeU16(12 + encodedName.size, type)
    message.writeU16(12 + encodedName.size + 2, 1)
    return message
}

class FakeDnsTableTest {
    private val base = 0x0A390000 // 10.57.0.0
    private val table = FakeDnsTable(base)

    @Test
    fun `the first names get consecutive addresses after the reserved block`() {
        assertEquals("10.57.1.0", formatIpv4(table.addressFor("uci.cu")))
        assertEquals("10.57.1.1", formatIpv4(table.addressFor("example.org")))
    }

    @Test
    fun `the same name always gets the same address regardless of case`() {
        val address = table.addressFor("Example.ORG")

        assertEquals(address, table.addressFor("example.org"))
        assertEquals("example.org", table.hostFor(address))
    }

    @Test
    fun `an address that was never handed out has no host`() {
        assertNull(table.hostFor(base or 0x0100))
    }

    @Test
    fun `contains tells the virtual subnet from everything else`() {
        assertTrue(table.contains(base or 0x1234))
        assertFalse(table.contains(0x0A3A0001))
        assertFalse(table.contains(0x08080808))
    }

    @Test
    fun `when the range is exhausted the oldest address is reused`() {
        val capacity = FakeDnsTable.LAST_OFFSET - FakeDnsTable.FIRST_OFFSET + 1
        val first = table.addressFor("host0.test")
        for (i in 1 until capacity) table.addressFor("host$i.test")

        val recycled = table.addressFor("nuevo.test")

        assertEquals(first, recycled)
        assertEquals("nuevo.test", table.hostFor(first))
        // El nombre antiguo ya no apunta a la dirección reutilizada: recibe otra.
        assertTrue(table.addressFor("host0.test") != first)
    }

    @Test
    fun `the base must be a 16 bit network`() {
        assertFailsWith<IllegalArgumentException> { FakeDnsTable(0x0A390100) }
    }
}

class DnsResponderTest {
    private val table = FakeDnsTable(0x0A390000)
    private val responder = DnsResponder(table)

    @Test
    fun `an A query is answered with the fake address of the name`() {
        val query = dnsQuery("www.uci.cu")

        val response = assertNotNull(responder.respond(query))

        assertEquals(0x1234, response.readU16(0), "mismo identificador")
        assertEquals(0x8180, response.readU16(2), "respuesta, recursión deseada y disponible, sin error")
        assertEquals(1, response.readU16(4), "una pregunta")
        assertEquals(1, response.readU16(6), "una respuesta")
        assertContentEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size), "pregunta repetida")

        val answer = query.size
        assertEquals(0xC00C, response.readU16(answer), "puntero al nombre de la pregunta")
        assertEquals(1, response.readU16(answer + 2), "tipo A")
        assertEquals(1, response.readU16(answer + 4), "clase IN")
        assertEquals(60, response.readInt(answer + 6), "TTL")
        assertEquals(4, response.readU16(answer + 10), "cuatro bytes de dirección")
        assertEquals(table.addressFor("www.uci.cu"), response.readInt(answer + 12))
        assertEquals(answer + 16, response.size)
    }

    @Test
    fun `a name with its own address is answered with it instead of the fake one`() {
        val own = 0x836BFFFF.toInt()
        val special = DnsResponder(table) { name -> if (name == "dns.msftncsi.com") own else null }

        val special1 = assertNotNull(special.respond(dnsQuery("dns.msftncsi.com")))
        val normal = assertNotNull(special.respond(dnsQuery("www.uci.cu")))

        assertEquals(own, special1.readInt(special1.size - 4))
        assertEquals(table.addressFor("www.uci.cu"), normal.readInt(normal.size - 4))
    }

    @Test
    fun `an AAAA query gets an empty answer without error`() {
        val query = dnsQuery("www.uci.cu", type = 28)

        val response = assertNotNull(responder.respond(query))

        assertEquals(0x8180, response.readU16(2))
        assertEquals(0, response.readU16(6))
        assertEquals(query.size, response.size)
    }

    @Test
    fun `the recursion desired bit is echoed only when the query set it`() {
        val response = assertNotNull(responder.respond(dnsQuery("uci.cu", flags = 0x0000)))

        assertEquals(0x8080, response.readU16(2))
    }

    @Test
    fun `the question is parsed with its name and type`() {
        val (question, length) = assertNotNull(responder.parseQuestion(dnsQuery("a.bc.def", type = 28)))

        assertEquals(DnsQuestion("a.bc.def", 28), question)
        assertEquals(1 + 1 + 1 + 2 + 1 + 3 + 1 + 4, length)
    }

    @Test
    fun `messages that are not valid queries are ignored`() {
        assertNull(responder.respond(ByteArray(5)), "más corto que la cabecera")
        assertNull(responder.respond(dnsQuery("uci.cu", flags = 0x8180)), "es una respuesta")
        assertNull(responder.respond(dnsQuery("uci.cu", questions = 0)), "sin preguntas")
        assertNull(responder.respond(dnsQuery("uci.cu").let { it.copyOf(it.size - 3) }), "truncado")
        assertNull(responder.respond(dnsQuery("")), "nombre vacío")
        assertNull(responder.respond(dnsQuery("uci.cu").also { it[12] = 0xC0.toByte() }), "puntero en la pregunta")
    }
}
