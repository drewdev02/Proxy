package org.uci.proxy.profile

import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** La lógica de las reglas de salida del editor, sin ViewModel: son funciones puras sobre el borrador. */
class RoutingDraftTest {
    private val netflix = RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN)
    private val lan = RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.DIRECT)
    private val maps = RouteRule(RuleKind.DOMAIN, "maps.google.com", RouteExit.PROXY)

    private fun draft(vararg rules: RouteRule) = RoutingDraft(rules = RoutingRules(enabled = true, rules = rules.toList()))

    private val RoutingDraft.list get() = rules.rules

    @Test
    fun `the exit of a new rule follows Other Country on and off`() {
        val proxy = RoutingDraft(newRuleExit = RouteExit.PROXY)
        val vpn = RoutingDraft(newRuleExit = RouteExit.VPN)

        assertEquals(RouteExit.VPN, proxy.withNewRuleExitFor(countryExitEnabled = true).newRuleExit)
        assertEquals(RouteExit.PROXY, vpn.withNewRuleExitFor(countryExitEnabled = false).newRuleExit)
    }

    @Test
    fun `an exit picked by hand is respected when Other Country changes`() {
        val direct = RoutingDraft(newRuleExit = RouteExit.DIRECT)
        val block = RoutingDraft(newRuleExit = RouteExit.BLOCK)

        assertSame(direct, direct.withNewRuleExitFor(true))
        assertSame(direct, direct.withNewRuleExitFor(false))
        assertSame(block, block.withNewRuleExitFor(true))
    }

    @Test
    fun `turning the rules on and picking the default exit only touch the rules`() {
        val result = RoutingDraft(newRule = "a.com").withEnabled(true).withDefaultExit(RouteExit.VPN)

        assertEquals(RoutingRules(enabled = true, defaultExit = RouteExit.VPN), result.rules)
        assertEquals("a.com", result.newRule)
    }

    @Test
    fun `typing a rule clears the invalid mark`() {
        val invalid = RoutingDraft(newRule = "???").markInvalid()

        assertTrue(invalid.newRuleInvalid)
        assertFalse(invalid.withNewRule("netflix.com").newRuleInvalid)
    }

    @Test
    fun `what is typed becomes a rule with the chosen exit`() {
        val typed = RoutingDraft(newRule = "https://Netflix.com/browse", newRuleExit = RouteExit.VPN)

        assertEquals(netflix, typed.parsedNewRule())
    }

    @Test
    fun `an address or a range is an IP rule`() {
        assertEquals(RouteRule(RuleKind.IP, "1.2.3.4", RouteExit.PROXY), RoutingDraft(newRule = "1.2.3.4").parsedNewRule())
        assertEquals(RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.PROXY), RoutingDraft(newRule = "10.1.2.3/8").parsedNewRule())
    }

    @Test
    fun `text that is neither a domain nor an address is not a rule`() {
        assertNull(RoutingDraft(newRule = "").parsedNewRule())
        assertNull(RoutingDraft(newRule = "no es un sitio").parsedNewRule())
        assertNull(RoutingDraft(newRule = "-malo-.com").parsedNewRule())
    }

    @Test
    fun `adding a rule puts it last and clears the field`() {
        val result = draft(lan).copy(newRule = "netflix.com", newRuleInvalid = true).withRule(netflix, clearText = true)

        assertEquals(listOf(lan, netflix), result.list)
        assertEquals("", result.newRule)
        assertFalse(result.newRuleInvalid)
    }

    @Test
    fun `a rule for something already in the list replaces the old one and goes last`() {
        val result = draft(netflix, lan).withRule(netflix.copy(exit = RouteExit.BLOCK), clearText = true)

        assertEquals(listOf(lan, netflix.copy(exit = RouteExit.BLOCK)), result.list)
    }

    @Test
    fun `an app rule uses the exit of a new rule and keeps what is typed`() {
        val result = RoutingDraft(newRule = "algo", newRuleExit = RouteExit.BLOCK, newRuleInvalid = true).withAppRule("com.netflix.mediaclient")

        assertEquals(listOf(RouteRule(RuleKind.APP, "com.netflix.mediaclient", RouteExit.BLOCK)), result.list)
        assertEquals("algo", result.newRule)
        assertTrue(result.newRuleInvalid)
    }

    @Test
    fun `a rule changes its exit by position`() {
        val result = draft(netflix, lan, maps).withRuleExit(1, RouteExit.BLOCK)

        assertEquals(listOf(netflix, lan.copy(exit = RouteExit.BLOCK), maps), result.list)
    }

    @Test
    fun `a rule moves up and down`() {
        val start = draft(netflix, lan, maps)

        assertEquals(listOf(netflix, maps, lan), start.withRuleMoved(1, +1).list)
        assertEquals(listOf(lan, netflix, maps), start.withRuleMoved(1, -1).list)
    }

    @Test
    fun `moving past either end changes nothing`() {
        val start = draft(netflix, lan)

        assertSame(start, start.withRuleMoved(0, -1))
        assertSame(start, start.withRuleMoved(1, +1))
        assertSame(start, start.withRuleMoved(5, -1))
    }

    @Test
    fun `a rule is removed by position and a wrong position removes nothing`() {
        val start = draft(netflix, lan, maps)

        assertEquals(listOf(netflix, maps), start.withRuleRemoved(1).list)
        assertEquals(start.list, start.withRuleRemoved(9).list)
    }
}
