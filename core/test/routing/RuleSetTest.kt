package org.uci.proxy.core.routing

import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.parseIpv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleSetTest {
    private fun ip(text: String) = parseIpv4(text)!!

    private fun rules(vararg rules: RouteRule, default: RouteExit = RouteExit.PROXY) =
        RuleSet(RoutingRules(enabled = true, defaultExit = default, rules = rules.toList()))

    @Test
    fun `a domain covers itself and its subdomains but not look-alikes`() {
        val set = rules(RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN))

        assertEquals(RouteExit.VPN, set.exitFor("netflix.com", null))
        assertEquals(RouteExit.VPN, set.exitFor("www.NETFLIX.com.", null), "sin distinguir mayúsculas ni el punto final")
        assertEquals(RouteExit.VPN, set.exitFor("a.b.netflix.com", null))
        assertEquals(RouteExit.PROXY, set.exitFor("notnetflix.com", null))
        assertEquals(RouteExit.PROXY, set.exitFor("netflix.com.evil.cu", null))
    }

    @Test
    fun `wildcards match any part of the name`() {
        val set = rules(RouteRule(RuleKind.DOMAIN, "*.google.*", RouteExit.VPN))

        assertEquals(RouteExit.VPN, set.exitFor("www.google.com", null))
        assertEquals(RouteExit.VPN, set.exitFor("mail.google.co.uk", null))
        assertEquals(RouteExit.PROXY, set.exitFor("google.com", null), "*. pide al menos un subdominio")
    }

    @Test
    fun `IP rules match real addresses only`() {
        val set = rules(RouteRule(RuleKind.IP, "149.22.0.0/16", RouteExit.VPN))

        assertEquals(RouteExit.VPN, set.exitFor(null, ip("149.22.82.28")))
        assertEquals(RouteExit.PROXY, set.exitFor(null, ip("149.23.0.1")))
        assertEquals(RouteExit.PROXY, set.exitFor("x.cu", null), "una dirección inventada no es una IP real")
    }

    @Test
    fun `the first matching rule wins and the default covers the rest`() {
        val set = rules(
            RouteRule(RuleKind.DOMAIN, "uci.cu", RouteExit.PROXY),
            RouteRule(RuleKind.DOMAIN, "cu", RouteExit.BLOCK),
            default = RouteExit.VPN,
        )

        assertEquals(RouteExit.PROXY, set.exitFor("www.uci.cu", null))
        assertEquals(RouteExit.BLOCK, set.exitFor("www.etecsa.cu", null))
        assertEquals(RouteExit.VPN, set.exitFor("example.com", null))
        assertEquals(RouteExit.VPN, set.exitFor(null, ip("8.8.8.8")))
    }

    @Test
    fun `app rules match the app that opened the connection`() {
        val set = rules(
            RouteRule(RuleKind.APP, "com.netflix.mediaclient", RouteExit.VPN),
            RouteRule(RuleKind.APP, "cu.uci.correo", RouteExit.DIRECT),
        )

        assertEquals(RouteExit.VPN, set.exitFor("www.google.com", null, apps = listOf("com.netflix.mediaclient")))
        assertEquals(RouteExit.PROXY, set.exitFor("www.google.com", null, apps = listOf("com.android.chrome")))
        assertEquals(RouteExit.PROXY, set.exitFor("com.netflix.mediaclient", null), "el nombre de la app no es un dominio")
        assertTrue(set.hasAppRules)
        assertEquals(listOf("cu.uci.correo"), set.directApps)
    }

    @Test
    fun `direct-only app rules need no lookup`() {
        assertFalse(rules(RouteRule(RuleKind.APP, "cu.uci.correo", RouteExit.DIRECT)).hasAppRules)
    }

    @Test
    fun `direct IP rules become routes outside the tunnel`() {
        val set = rules(
            RouteRule(RuleKind.IP, "200.55.0.0/16", RouteExit.DIRECT),
            RouteRule(RuleKind.IP, "1.1.1.1", RouteExit.DIRECT),
            RouteRule(RuleKind.IP, "8.8.8.8", RouteExit.VPN),
        )

        assertEquals(listOf(Ipv4Route.parse("200.55.0.0/16"), Ipv4Route.parse("1.1.1.1/32")), set.directRoutes)
    }

    @Test
    fun `typed text becomes a domain or an IP rule`() {
        assertEquals(RouteRule(RuleKind.DOMAIN, "www.netflix.com", RouteExit.VPN), parseRule(" https://www.Netflix.com/browse ", RouteExit.VPN))
        assertEquals(RouteRule(RuleKind.DOMAIN, "chatgpt.com", RouteExit.VPN), parseRule("chatgpt.com:443", RouteExit.VPN))
        assertEquals(RouteRule(RuleKind.DOMAIN, "*.google.*", RouteExit.VPN), parseRule("*.google.*", RouteExit.VPN))
        assertEquals(RouteRule(RuleKind.IP, "1.2.3.4", RouteExit.DIRECT), parseRule("1.2.3.4", RouteExit.DIRECT))
        assertEquals(RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.BLOCK), parseRule("10.1.2.3/8", RouteExit.BLOCK), "a su dirección de red")
    }

    @Test
    fun `nonsense is not a rule`() {
        listOf("", "   ", "*", "*.*", "hola mundo", "a..b", "-x.com", "1.2.3.4/40", "ñandú.com").forEach {
            assertNull(parseRule(it, RouteExit.VPN), "«$it» no debería valer")
        }
    }

    @Test
    fun `the VPN is only needed when some rule or the default uses it`() {
        assertFalse(RoutingRules(enabled = true).usesVpn)
        assertTrue(RoutingRules(enabled = true, defaultExit = RouteExit.VPN).usesVpn)
        assertTrue(RoutingRules(enabled = true, rules = listOf(RouteRule(RuleKind.DOMAIN, "x.com", RouteExit.VPN))).usesVpn)
        assertFalse(RoutingRules(enabled = false, defaultExit = RouteExit.VPN).usesVpn, "apagadas no cuentan")
    }

    @Test
    fun `the first matching domain rule gives the exit known when resolving a name`() {
        val set = rules(
            RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.PROXY),
            RouteRule(RuleKind.DOMAIN, "correo.uci.cu", RouteExit.DIRECT),
            RouteRule(RuleKind.DOMAIN, "*.uci.cu", RouteExit.PROXY),
            default = RouteExit.VPN,
        )

        assertEquals(RouteExit.DIRECT, set.domainExitFor("Correo.UCI.cu."))
        assertEquals(RouteExit.PROXY, set.domainExitFor("drst.uci.cu"))
        assertEquals(null, set.domainExitFor("www.google.com"), "sin regla: ni la de IP ni la de por defecto cuentan")
    }

    @Test
    fun `domain rules accept every exit`() {
        assertEquals(RouteExit.entries, RoutingRules.exitsFor(RuleKind.DOMAIN))
    }
}
