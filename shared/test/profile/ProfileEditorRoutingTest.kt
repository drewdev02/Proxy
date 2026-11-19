package org.uci.proxy.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeProxyTester
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** La sección «Reglas de salida» del editor de perfiles. */
class ProfileEditorRoutingTest : MainDispatcherTest() {
    private val repository = FakeProfileRepository()

    private fun newViewModel() = ProfileEditorViewModel(
        repository, ProfileValidator(), idGenerator = { "nuevo" }, FakeProxyTester(), FakeLocalNetworkPermission(), RecordingLogger(),
    ).also { it.start(null) }

    private val ProfileEditorViewModel.rules get() = uiState.value.routing.rules

    private fun ProfileEditorViewModel.add(text: String, exit: RouteExit) {
        updateNewRule(text)
        setNewRuleExit(exit)
        addRule()
    }

    @Test
    fun `a new profile has the rules off`() {
        assertFalse(newViewModel().rules.enabled)
    }

    @Test
    fun `typed rules are added in order and the field is cleared`() {
        val viewModel = newViewModel()

        viewModel.add("netflix.com", RouteExit.VPN)
        viewModel.add("200.55.0.0/16", RouteExit.DIRECT)

        assertEquals(
            listOf(RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN), RouteRule(RuleKind.IP, "200.55.0.0/16", RouteExit.DIRECT)),
            viewModel.rules.rules,
        )
        assertEquals("", viewModel.uiState.value.routing.newRule)
    }

    @Test
    fun `text that is not a domain or an IP is flagged and not added`() {
        val viewModel = newViewModel()

        viewModel.add("hola mundo", RouteExit.VPN)

        assertTrue(viewModel.uiState.value.routing.newRuleInvalid)
        assertTrue(viewModel.rules.rules.isEmpty())
        viewModel.updateNewRule("hola.com")
        assertFalse(viewModel.uiState.value.routing.newRuleInvalid, "al corregir se retira el aviso")
    }

    @Test
    fun `a domain cannot go direct yet so it goes through the proxy`() {
        val viewModel = newViewModel()

        viewModel.add("etecsa.cu", RouteExit.DIRECT)

        assertEquals(RouteExit.PROXY, viewModel.rules.rules.single().exit)
    }

    @Test
    fun `a chosen app gets the exit picked for the new rule and keeps the typed text`() {
        val viewModel = newViewModel()
        viewModel.updateNewRule("a medio escribir")
        viewModel.setNewRuleExit(RouteExit.DIRECT)

        viewModel.addAppRule("cu.uci.correo")

        assertEquals(listOf(RouteRule(RuleKind.APP, "cu.uci.correo", RouteExit.DIRECT)), viewModel.rules.rules)
        assertEquals("a medio escribir", viewModel.uiState.value.routing.newRule)
    }

    @Test
    fun `choosing the same app again replaces its rule`() {
        val viewModel = newViewModel()
        viewModel.setNewRuleExit(RouteExit.VPN)
        viewModel.addAppRule("com.netflix.mediaclient")
        viewModel.setNewRuleExit(RouteExit.BLOCK)

        viewModel.addAppRule("com.netflix.mediaclient")

        assertEquals(RouteExit.BLOCK, viewModel.rules.rules.single().exit)
    }

    @Test
    fun `adding the same rule again replaces it at the end`() {
        val viewModel = newViewModel()
        viewModel.add("a.com", RouteExit.VPN)
        viewModel.add("b.com", RouteExit.VPN)

        viewModel.add("a.com", RouteExit.BLOCK)

        assertEquals(listOf("b.com" to RouteExit.VPN, "a.com" to RouteExit.BLOCK), viewModel.rules.rules.map { it.value to it.exit })
    }

    @Test
    fun `rules can be reordered and changed and removed`() {
        val viewModel = newViewModel()
        viewModel.add("a.com", RouteExit.VPN)
        viewModel.add("b.com", RouteExit.VPN)
        viewModel.add("c.com", RouteExit.VPN)

        viewModel.moveRule(2, -1)
        viewModel.moveRule(0, -1) // ya está arriba: no cambia nada
        viewModel.setRuleExit(0, RouteExit.BLOCK)
        viewModel.removeRule(2)

        assertEquals(listOf("a.com" to RouteExit.BLOCK, "c.com" to RouteExit.VPN), viewModel.rules.rules.map { it.value to it.exit })
    }

    @Test
    fun `the rules are saved with the profile`() {
        val viewModel = newViewModel()
        viewModel.updateDraft(ProfileDraft("UCI", "proxy.uci.cu", "3128", ProxyProtocol.HTTP, "andrey"))
        viewModel.setRoutingEnabled(true)
        viewModel.setDefaultExit(RouteExit.VPN)
        viewModel.add("uci.cu", RouteExit.PROXY)

        viewModel.save()

        assertEquals(
            RoutingRules(enabled = true, defaultExit = RouteExit.VPN, rules = listOf(RouteRule(RuleKind.DOMAIN, "uci.cu", RouteExit.PROXY))),
            repository.profiles.value.single().routing,
        )
    }

    @Test
    fun `editing a profile loads its rules`() {
        val routing = RoutingRules(enabled = true, rules = listOf(RouteRule(RuleKind.DOMAIN, "x.com", RouteExit.BLOCK)))
        val seeded = FakeProfileRepository(listOf(ProxyProfile("p1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "", routing = routing)))
        val viewModel = ProfileEditorViewModel(
            seeded, ProfileValidator(), idGenerator = { "nuevo" }, FakeProxyTester(), FakeLocalNetworkPermission(), RecordingLogger(),
        ).also { it.start("p1") }

        assertEquals(routing, viewModel.rules)
    }
}
