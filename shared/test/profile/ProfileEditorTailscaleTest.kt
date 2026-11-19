package org.uci.proxy.profile

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.CountryEngineCatalog
import org.uci.proxy.core.vpn.tailscale.ExitNodeListing
import org.uci.proxy.core.vpn.tailscale.TailscaleExitNode
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeProxyTester
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Un catálogo con los dos motores, cuya búsqueda de nodos responde lo que diga la prueba. */
private class FakeCatalog(
    override val available: List<CountryEngineKind> = listOf(CountryEngineKind.OPENVPN, CountryEngineKind.TAILSCALE),
    var listing: ExitNodeListing = ExitNodeListing.Found(emptyList()),
) : CountryEngineCatalog {
    val searches = mutableListOf<Triple<ProxyProfile, String, Pair<CountryExit.Tailscale, CountryExitSecrets.Tailscale>>>()

    override suspend fun listTailscaleExitNodes(
        proxy: ProxyProfile,
        proxyPassword: String,
        settings: AppSettings,
        exit: CountryExit.Tailscale,
        secrets: CountryExitSecrets.Tailscale,
    ): ExitNodeListing {
        searches += Triple(proxy, proxyPassword, exit to secrets)
        return listing
    }
}

/** El motor Tailscale en el editor de perfiles: elegirlo, validarlo, buscar nodos y guardarlo. */
class ProfileEditorTailscaleTest : MainDispatcherTest() {
    private val repository = FakeProfileRepository()
    private val catalog = FakeCatalog()
    private val validDraft = ProfileDraft("UCI", "proxy.uci.cu", "3128", ProxyProtocol.HTTP, "andrey")
    private val canada = TailscaleExitNode(id = "n1", name = "nodo-ca", ip = "100.64.0.7", online = true, country = "Canadá", city = "Toronto")

    private fun newViewModel(catalog: CountryEngineCatalog = this.catalog, repository: FakeProfileRepository = this.repository, id: String? = null) =
        ProfileEditorViewModel(
            repository, ProfileValidator(), idGenerator = { "nuevo" }, FakeProxyTester(), FakeLocalNetworkPermission(), RecordingLogger(),
            catalog = catalog,
        ).also { it.start(id) }

    private fun ProfileEditorViewModel.tailscaleProfile(key: String = "tskey-auth-abc") {
        onDraftChange(validDraft)
        onPasswordChange("secreta")
        onVpnEnabledChange(true)
        onEngineChange(CountryEngineKind.TAILSCALE)
        onTailscaleAuthKeyChange(key)
    }

    @Test
    fun `the engines of this device are offered with OpenVPN first`() {
        val state = newViewModel().uiState.value

        assertEquals(listOf(CountryEngineKind.OPENVPN, CountryEngineKind.TAILSCALE), state.engines)
        assertEquals(CountryEngineKind.OPENVPN, state.vpn.engine)
    }

    @Test
    fun `an engine that is not available cannot be chosen`() {
        val viewModel = newViewModel(catalog = FakeCatalog(available = listOf(CountryEngineKind.OPENVPN)))

        viewModel.onEngineChange(CountryEngineKind.TAILSCALE)

        assertEquals(CountryEngineKind.OPENVPN, viewModel.uiState.value.vpn.engine)
    }

    @Test
    fun `a Tailscale exit is saved with its configuration and the key goes to the secrets`() {
        val viewModel = newViewModel()
        viewModel.tailscaleProfile(key = "  tskey-auth-abc  ")
        viewModel.onTailscaleControlUrlChange("https://headscale.ejemplo.org/")
        viewModel.onTailscaleExitNodeChange("nodo-ca")
        viewModel.onTailscaleHostnameChange("portatil")

        viewModel.save()

        val saved = repository.profiles.value.single()
        assertEquals(CountryExit.Tailscale("https://headscale.ejemplo.org", "nodo-ca", "portatil"), saved.countryExit)
        assertEquals(CountryExitSecrets.Tailscale("tskey-auth-abc"), repository.vpnSecrets[saved.id])
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `a Tailscale exit needs a key and a valid control server`() {
        val viewModel = newViewModel()
        viewModel.tailscaleProfile(key = "")
        viewModel.onTailscaleControlUrlChange("headscale.ejemplo.org")

        viewModel.save()

        assertEquals(setOf(ProfileField.TAILSCALE_AUTH_KEY, ProfileField.TAILSCALE_CONTROL_URL), viewModel.uiState.value.errors)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun `switching back to OpenVPN validates the OpenVPN fields instead of the Tailscale ones`() {
        val viewModel = newViewModel()
        viewModel.tailscaleProfile(key = "")
        viewModel.onEngineChange(CountryEngineKind.OPENVPN)

        viewModel.save()

        assertEquals(setOf(ProfileField.VPN_FILE, ProfileField.VPN_USERNAME), viewModel.uiState.value.errors)
    }

    @Test
    fun `searching finds the exit nodes through the form's proxy and picks the only one`() {
        catalog.listing = ExitNodeListing.Found(listOf(canada))
        val viewModel = newViewModel()
        viewModel.tailscaleProfile()

        viewModel.searchExitNodes()

        val tailscale = viewModel.uiState.value.vpn.tailscale
        assertEquals(ExitNodeSearch.Found(listOf(canada)), tailscale.search)
        assertEquals("nodo-ca", tailscale.exitNode, "con un solo nodo, queda elegido")
        val (proxy, password, request) = catalog.searches.single()
        assertEquals("proxy.uci.cu", proxy.host)
        assertEquals("secreta", password)
        assertEquals(CountryExitSecrets.Tailscale("tskey-auth-abc"), request.second)
        assertTrue(repository.profiles.value.isEmpty(), "buscar no guarda el perfil")
    }

    @Test
    fun `a chosen exit node is not replaced by the search`() {
        catalog.listing = ExitNodeListing.Found(listOf(canada))
        val viewModel = newViewModel()
        viewModel.tailscaleProfile()
        viewModel.onTailscaleExitNodeChange("otro")

        viewModel.searchExitNodes()

        assertEquals("otro", viewModel.uiState.value.vpn.tailscale.exitNode)
    }

    @Test
    fun `a search rejected by the network explains why`() {
        catalog.listing = ExitNodeListing.Failed(TunnelFailure.VPN_AUTH_FAILED, "invalid key")
        val viewModel = newViewModel()
        viewModel.tailscaleProfile()

        viewModel.searchExitNodes()

        val search = assertIs<ExitNodeSearch.Failed>(viewModel.uiState.value.vpn.tailscale.search)
        assertEquals("La red rechazó la clave de autenticación.", search.message)
    }

    @Test
    fun `searching without a key asks for it instead of searching`() {
        val viewModel = newViewModel()
        viewModel.tailscaleProfile(key = "")

        viewModel.searchExitNodes()

        assertTrue(ProfileField.TAILSCALE_AUTH_KEY in viewModel.uiState.value.errors)
        assertTrue(catalog.searches.isEmpty())
    }

    @Test
    fun `editing a stored Tailscale profile brings back its configuration and key`() {
        val stored = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey", CountryExit.Tailscale(exitNode = "nodo-ca"))
        val seeded = FakeProfileRepository(listOf(stored)).apply { vpnSecrets["a1"] = CountryExitSecrets.Tailscale("tskey-auth-abc") }

        val vpn = newViewModel(repository = seeded, id = "a1").uiState.value.vpn

        assertEquals(CountryEngineKind.TAILSCALE, vpn.engine)
        assertTrue(vpn.enabled)
        assertEquals("nodo-ca", vpn.tailscale.exitNode)
        assertEquals("tskey-auth-abc", vpn.tailscale.authKey)
    }
}
