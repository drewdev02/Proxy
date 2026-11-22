package org.uci.proxy.core

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.buildDnsQuery
import org.uci.proxy.core.dns.dnsAnswerAddresses
import org.uci.proxy.core.net.KtorDnsTransport
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.common.AppDispatchers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** UDP real contra un servidor DNS de juguete en 127.0.0.1. */
class KtorDnsTransportTest {
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val transport = KtorDnsTransport(selectorManager, AppDispatchers(background = Dispatchers.IO), port = server.localPort)

    @AfterTest
    fun cleanUp() {
        server.close()
        selectorManager.close()
    }

    /** Contesta una consulta: antes, si [strayFirst], manda una respuesta con otro identificador. */
    private fun answerOnce(address: String, strayFirst: Boolean = false) = thread {
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        server.receive(packet)
        val query = buffer.copyOf(packet.length)
        if (strayFirst) {
            val stray = query.copyOf().also { it[0] = (it[0] + 1).toByte() }
            val wrong = DnsResponder.reply(stray, 1)!!
            server.send(DatagramPacket(wrong, wrong.size, packet.socketAddress))
        }
        val reply = DnsResponder.reply(query, parseIpv4(address))!!
        server.send(DatagramPacket(reply, reply.size, packet.socketAddress))
    }

    @Test
    fun `a query gets its answer`() = runBlocking {
        answerOnce("10.0.0.13")

        val reply = assertNotNull(transport.exchange("127.0.0.1", buildDnsQuery("drst.uci.cu", 7), 2_000))

        assertEquals(listOf(parseIpv4("10.0.0.13")!!), dnsAnswerAddresses(reply))
    }

    @Test
    fun `an answer to another query is ignored`() = runBlocking {
        answerOnce("10.0.0.13", strayFirst = true)

        val reply = assertNotNull(transport.exchange("127.0.0.1", buildDnsQuery("drst.uci.cu", 7), 2_000))

        assertEquals(listOf(parseIpv4("10.0.0.13")!!), dnsAnswerAddresses(reply))
    }

    @Test
    fun `no answer in time is null`() = runBlocking {
        assertNull(transport.exchange("127.0.0.1", buildDnsQuery("drst.uci.cu", 7), 200))
    }
}
