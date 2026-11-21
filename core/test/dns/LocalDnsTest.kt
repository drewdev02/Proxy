package org.uci.proxy.core.dns

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.FakeDnsTransport
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.routing.RuleSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocalDnsPolicyTest {
    private fun rules(vararg rules: RouteRule) = RuleSet(RoutingRules(enabled = true, defaultExit = RouteExit.VPN, rules = rules.toList()))

    @Test
    fun `names under the network's search domains are local`() {
        val policy = LocalDnsPolicy(listOf("uci.cu"), intranetDirect = true)

        assertTrue(policy.claims("drst.uci.cu"))
        assertTrue(policy.claims("gitlab.prod.uci.cu."))
        assertTrue(policy.claims("UCI.CU"))
        assertFalse(policy.claims("notuci.cu"), "no basta con terminar igual")
        assertFalse(policy.claims("www.google.com"))
    }

    @Test
    fun `with the setting off only direct rules count`() {
        val policy = LocalDnsPolicy(listOf("uci.cu"), intranetDirect = false, rules(RouteRule(RuleKind.DOMAIN, "intranet.example", RouteExit.DIRECT)))

        assertFalse(policy.claims("drst.uci.cu"))
        assertTrue(policy.claims("wiki.intranet.example"))
    }

    @Test
    fun `an explicit domain rule wins over the setting`() {
        val policy = LocalDnsPolicy(
            listOf("uci.cu"),
            intranetDirect = true,
            rules(
                RouteRule(RuleKind.DOMAIN, "correo.uci.cu", RouteExit.DIRECT),
                RouteRule(RuleKind.DOMAIN, "*.uci.cu", RouteExit.PROXY),
            ),
        )

        assertTrue(policy.claims("correo.uci.cu"), "la primera que coincide es «Directo»")
        assertFalse(policy.claims("drst.uci.cu"), "«*.uci.cu → Proxy» manda sobre el ajuste")
    }

    @Test
    fun `WPAD is recognized with or without a domain`() {
        assertTrue(LocalDnsPolicy.isWpad("wpad"))
        assertTrue(LocalDnsPolicy.isWpad("WPAD.uci.cu."))
        assertFalse(LocalDnsPolicy.isWpad("wpadx.uci.cu"))
        assertFalse(LocalDnsPolicy.isWpad("www.wpad.com"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDnsResolverTest {
    private val logger = RecordingLogger()
    private val tunnelSubnet = Ipv4Route.parse("10.63.0.0/16")
    private val zones = mapOf(
        "10.0.0.3" to mapOf(
            "drst.uci.cu" to listOf("10.0.0.13"),
            "www.uci.cu" to listOf("200.55.140.3"),
            "choca.uci.cu" to listOf("10.63.1.5"),
            "vacio.uci.cu" to emptyList(),
        ),
    )

    private fun TestScope.resolver(transport: FakeDnsTransport, servers: List<String> = listOf("10.0.0.3")) =
        LocalDnsResolver(servers, transport, listOf(tunnelSubnet), logger, testScheduler.timeSource)

    @Test
    fun `an internal address is given and a public one is not`() = runTest {
        val resolver = resolver(FakeDnsTransport(zones))

        assertEquals("10.0.0.13", formatIpv4(assertNotNull(resolver.resolve("drst.uci.cu"))))
        assertNull(resolver.resolve("www.uci.cu"), "pública: sale como siempre")
        assertNull(resolver.resolve("vacio.uci.cu"))
    }

    @Test
    fun `an address inside the tunnel's own subnet cannot be reached directly`() = runTest {
        val resolver = resolver(FakeDnsTransport(zones))

        assertNull(resolver.resolve("choca.uci.cu"))
        assertTrue(logger.entries.any { "subred del túnel" in it.second })
    }

    @Test
    fun `only internal servers are used`() = runTest {
        val transport = FakeDnsTransport(zones)
        val resolver = resolver(transport, servers = listOf("1.1.1.1", "10.0.0.3", "8.8.8.8", "no-es-ip"))

        assertEquals(listOf("10.0.0.3"), resolver.servers)
        resolver.resolve("drst.uci.cu")
        assertEquals(listOf("10.0.0.3" to "drst.uci.cu"), transport.asked)
    }

    @Test
    fun `without internal servers nothing is asked`() = runTest {
        val transport = FakeDnsTransport(zones)
        val resolver = resolver(transport, servers = listOf("1.1.1.1"))

        assertNull(resolver.resolve("drst.uci.cu"))
        assertTrue(transport.asked.isEmpty())
    }

    @Test
    fun `a server that does not answer is skipped`() = runTest {
        val transport = FakeDnsTransport(zones)
        val resolver = resolver(transport, servers = listOf("10.0.0.4", "10.0.0.3"))

        assertEquals("10.0.0.13", formatIpv4(assertNotNull(resolver.resolve("drst.uci.cu"))))
        assertEquals(listOf("10.0.0.4", "10.0.0.3"), transport.asked.map { it.first })
    }

    @Test
    fun `answers are remembered for a while`() = runTest {
        val transport = FakeDnsTransport(zones)
        val resolver = resolver(transport)

        resolver.resolve("drst.uci.cu")
        resolver.resolve("DRST.uci.cu.")
        assertEquals(1, transport.asked.size)
        assertEquals(10, resolver.known("drst.uci.cu")?.address?.ushr(24))

        testScheduler.advanceTimeBy(121.seconds)
        assertNull(resolver.known("drst.uci.cu"), "caducado")
        resolver.resolve("drst.uci.cu")
        assertEquals(2, transport.asked.size)
    }

    @Test
    fun `a name that is not internal is remembered too, for less time`() = runTest {
        val transport = FakeDnsTransport(zones)
        val resolver = resolver(transport)

        resolver.resolve("www.uci.cu")
        assertNull(assertNotNull(resolver.known("www.uci.cu")).address)
        testScheduler.advanceTimeBy(31.seconds)
        assertNull(resolver.known("www.uci.cu"))
    }

    @Test
    fun `simultaneous questions for the same name ask only once`() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeDnsTransport(zones).apply { gate = CompletableDeferred() }
        val resolver = resolver(transport)

        val first = async { resolver.resolve("drst.uci.cu") }
        val second = async { resolver.resolve("drst.uci.cu") }
        transport.gate!!.complete(Unit)

        assertEquals(first.await(), second.await())
        assertNotNull(first.await())
        assertEquals(1, transport.asked.size)
    }
}

class LocalDnsConfigTest {
    @Test
    fun `what the system reports is cleaned up`() {
        val config = LocalDnsConfig.of(
            servers = listOf(" 10.0.0.3", "fe80::1", "10.0.0.3", "10.0.0.4"),
            searchDomains = listOf("UCI.cu.", "", "local", "uci.cu", "prod.uci.cu", "mal;dominio"),
        )

        assertEquals(listOf("10.0.0.3", "10.0.0.4"), config.servers)
        assertEquals(listOf("uci.cu", "prod.uci.cu"), config.searchDomains)
    }

    @Test
    fun `internal addresses are the private and carrier ranges`() {
        assertTrue(isIntranetAddress(parseIpv4("10.0.0.13")!!))
        assertTrue(isIntranetAddress(parseIpv4("192.168.1.1")!!))
        assertTrue(isIntranetAddress(parseIpv4("172.20.0.1")!!))
        assertFalse(isIntranetAddress(parseIpv4("200.55.140.3")!!))
        assertFalse(isIntranetAddress(parseIpv4("127.0.0.1")!!), "el bucle local no es la red")
    }
}
