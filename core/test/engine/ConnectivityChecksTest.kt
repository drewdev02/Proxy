package org.uci.proxy.core.engine

import org.uci.proxy.core.packet.formatIpv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class ConnectivityChecksTest {
    private val time = TestTimeSource()
    private val health = ProxyHealth(time).also { it.markSuccess() }
    private val checks = ConnectivityChecks(enabled = true, health = health)

    private fun request(path: String, host: String) = "GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".encodeToByteArray()

    private fun ByteArray.text() = decodeToString()

    @Test
    fun `Android check gets an empty 204`() {
        val reply = checks.replyFor("connectivitycheck.gstatic.com", 80, request("/generate_204", "connectivitycheck.gstatic.com"))

        assertNotNull(reply)
        assertTrue(reply.text().startsWith("HTTP/1.1 204 No Content\r\n"))
        assertTrue(reply.text().endsWith("\r\n\r\n"), "sin cuerpo")
    }

    @Test
    fun `Windows checks get the exact text they expect`() {
        val connectTest = checks.replyFor("www.msftconnecttest.com", 80, request("/connecttest.txt", "www.msftconnecttest.com"))
        val ncsi = checks.replyFor("www.msftncsi.com", 80, request("/ncsi.txt", "www.msftncsi.com"))

        assertTrue(connectTest!!.text().startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(connectTest.text().endsWith("\r\n\r\nMicrosoft Connect Test"))
        assertTrue(ncsi!!.text().endsWith("\r\n\r\nMicrosoft NCSI"))
    }

    @Test
    fun `macOS check gets the Success page`() {
        val reply = checks.replyFor("captive.apple.com", 80, request("/hotspot-detect.html", "captive.apple.com"))!!.text()

        assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(reply.endsWith("\r\n\r\n<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"))
        assertNotNull(checks.replyFor("www.apple.com", 80, request("/library/test/success.html", "www.apple.com")))
        assertNull(checks.replyFor("www.apple.com", 80, request("/mac/", "www.apple.com")), "el resto de www.apple.com va al proxy")
    }

    @Test
    fun `the content length matches the body`() {
        val reply = checks.replyFor("www.msftncsi.com", 80, request("/ncsi.txt", "www.msftncsi.com"))!!.text()

        assertTrue("Content-Length: 14\r\n" in reply, reply)
    }

    @Test
    fun `host names are matched ignoring case and query strings`() {
        assertNotNull(checks.replyFor("WWW.MSFTNCSI.COM", 80, request("/ncsi.txt?x=1", "www.msftncsi.com")))
    }

    @Test
    fun `only the known path of a known host is answered`() {
        assertNull(checks.replyFor("www.google.com", 80, request("/search?q=uci", "www.google.com")))
        assertNull(checks.replyFor("www.msftncsi.com", 80, ByteArray(0)))
        assertNull(checks.replyFor("example.com", 80, request("/generate_204", "example.com")))
    }

    @Test
    fun `only plain HTTP requests are answered`() {
        assertNull(checks.replyFor("www.msftncsi.com", 443, request("/ncsi.txt", "www.msftncsi.com")))
        assertNull(checks.replyFor("www.msftncsi.com", 80, "POST /ncsi.txt HTTP/1.1\r\n\r\n".encodeToByteArray()))
        assertNull(checks.replyFor("www.msftncsi.com", 80, "basura".encodeToByteArray()))
    }

    @Test
    fun `nothing is answered when the setting is off`() {
        val off = ConnectivityChecks(enabled = false, health = health)

        assertNull(off.replyFor("www.msftncsi.com", 80, request("/ncsi.txt", "www.msftncsi.com")))
        assertNull(off.dnsAddressFor("dns.msftncsi.com"))
        assertFalse(off.mightBeCheck("www.msftncsi.com", 80))
    }

    @Test
    fun `nothing is answered until the proxy has worked and not after it stops working`() {
        val fresh = ConnectivityChecks(enabled = true, health = ProxyHealth(time))
        assertNull(fresh.replyFor("www.msftncsi.com", 80, request("/ncsi.txt", "www.msftncsi.com")))
        assertNull(fresh.dnsAddressFor("dns.msftncsi.com"))

        assertNotNull(checks.dnsAddressFor("dns.msftncsi.com"))
        time += 6.minutes
        assertNull(checks.replyFor("www.msftncsi.com", 80, request("/ncsi.txt", "www.msftncsi.com")))
        assertNull(checks.dnsAddressFor("dns.msftncsi.com"))
    }

    @Test
    fun `the proxy counts as working for five minutes after its last success`() {
        time += 4.minutes
        assertTrue(health.isHealthy())
        health.markSuccess()
        time += 4.minutes
        assertTrue(health.isHealthy())
        time += 2.minutes
        assertFalse(health.isHealthy())
    }

    @Test
    fun `the Windows DNS probe resolves to the address Windows expects`() {
        assertEquals("131.107.255.255", formatIpv4(checks.dnsAddressFor("dns.msftncsi.com")!!))
        assertEquals("131.107.255.255", formatIpv4(checks.dnsAddressFor("DNS.MSFTNCSI.COM")!!))
        assertNull(checks.dnsAddressFor("www.msftncsi.com"))
    }

    @Test
    fun `only known hosts on port 80 are worth reading`() {
        assertTrue(checks.mightBeCheck("connectivitycheck.gstatic.com", 80))
        assertFalse(checks.mightBeCheck("connectivitycheck.gstatic.com", 443))
        assertFalse(checks.mightBeCheck("example.com", 80))
    }
}
