package org.uci.proxy.profile

import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.vpn.OvpnConfigError
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeProxyTester
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** La sección «Salir por otro país (VPN)» del editor de perfiles. */
class ProfileEditorVpnTest : MainDispatcherTest() {
    private val repository = FakeProfileRepository()
    private val validDraft = ProfileDraft("UCI", "proxy.uci.cu", "3128", ProxyProtocol.HTTP, "andrey")

    private val keyHex = (0 until 256).joinToString("") { it.toString(16).padStart(2, '0') }
    private val goodOvpn = "client\nproto tcp\nremote 192.0.2.10 443\nauth-user-pass\n" +
        "<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n" +
        "<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\n${keyHex.chunked(32).joinToString("\n")}\n-----END OpenVPN Static key V1-----\n</tls-crypt>\n"
    private val udpOvpn = goodOvpn.replace("proto tcp", "proto udp")

    private fun newViewModel() = ProfileEditorViewModel(
        repository, ProfileValidator(), idGenerator = { "nuevo" }, FakeProxyTester(), FakeLocalNetworkPermission(), RecordingLogger(),
    ).also { it.start(null) }

    private fun ProfileEditorViewModel.fillValidProfile() = updateDraft(validDraft)

    @Test
    fun `a new profile goes straight out through the proxy`() {
        assertFalse(newViewModel().uiState.value.vpn.enabled)
    }

    @Test
    fun `choosing a valid file turns the VPN on and remembers its name`() {
        val viewModel = newViewModel()

        viewModel.loadOvpnFile("pais.ovpn", goodOvpn)

        val vpn = viewModel.uiState.value.vpn
        assertTrue(vpn.enabled)
        assertEquals("pais.ovpn", vpn.fileName)
        assertEquals(goodOvpn, vpn.ovpn)
        assertNull(vpn.fileError)
        assertTrue(vpn.needsCredentials)
    }

    @Test
    fun `a file that cannot be used is rejected right away with the reason`() {
        val viewModel = newViewModel()

        viewModel.loadOvpnFile("udp.ovpn", udpOvpn)

        val vpn = viewModel.uiState.value.vpn
        assertEquals("", vpn.ovpn)
        assertEquals(OvpnConfigError.NoTcpServers, vpn.fileError)
    }

    @Test
    fun `a profile with the VPN on is saved with its file and credentials`() = runTestSave { viewModel ->
        viewModel.fillValidProfile()
        viewModel.loadOvpnFile("pais.ovpn", goodOvpn)
        viewModel.updateVpnUsername("vpnuser")
        viewModel.updateVpnPassword("vpnpass")
        viewModel.save()

        val saved = repository.profiles.value.single()
        assertEquals(CountryExit.OpenVpn("pais.ovpn", "vpnuser"), saved.countryExit)
        assertEquals(CountryExitSecrets.OpenVpn(goodOvpn, "vpnpass"), repository.vpnSecrets[saved.id])
    }

    @Test
    fun `with the VPN on a file and a user are required`() {
        val viewModel = newViewModel()
        viewModel.fillValidProfile()
        viewModel.setCountryExitEnabled(true)

        viewModel.save()

        assertEquals(setOf(ProfileField.VPN_FILE, ProfileField.VPN_USERNAME), viewModel.uiState.value.errors)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun `a file whose server needs no credentials does not ask for a user`() {
        val viewModel = newViewModel()
        viewModel.fillValidProfile()

        viewModel.loadOvpnFile("abierta.ovpn", goodOvpn.replace("auth-user-pass\n", ""))
        viewModel.save()

        assertFalse(viewModel.uiState.value.vpn.needsCredentials)
        assertTrue(viewModel.uiState.value.errors.isEmpty())
    }

    @Test
    fun `with the VPN off nothing about it is saved`() = runTestSave { viewModel ->
        viewModel.fillValidProfile()
        viewModel.loadOvpnFile("pais.ovpn", goodOvpn)
        viewModel.setCountryExitEnabled(false)
        viewModel.save()

        val saved = repository.profiles.value.single()
        assertNull(saved.countryExit)
        assertNull(repository.vpnSecrets[saved.id])
    }

    @Test
    fun `errors in the VPN fields and the proxy fields are reported together`() {
        val viewModel = newViewModel()
        viewModel.setCountryExitEnabled(true)

        viewModel.save()

        val errors = viewModel.uiState.value.errors
        assertTrue(ProfileField.NAME in errors && ProfileField.VPN_FILE in errors)
    }

    @Test
    fun `fixing a VPN field clears its error`() {
        val viewModel = newViewModel()
        viewModel.fillValidProfile()
        viewModel.setCountryExitEnabled(true)
        viewModel.save()

        viewModel.updateVpnUsername("vpnuser")
        viewModel.loadOvpnFile("pais.ovpn", goodOvpn)

        assertTrue(viewModel.uiState.value.errors.isEmpty())
    }

    @Test
    fun `editing a profile with a VPN loads its file and password`() {
        val stored = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey", CountryExit.OpenVpn("pais.ovpn", "vpnuser"))
        val withVpn = FakeProfileRepository(listOf(stored)).apply { vpnSecrets["a1"] = CountryExitSecrets.OpenVpn(goodOvpn, "vpnpass") }
        val viewModel = ProfileEditorViewModel(
            withVpn, ProfileValidator(), { "nuevo" }, FakeProxyTester(), FakeLocalNetworkPermission(), RecordingLogger(),
        )

        viewModel.start("a1")

        val vpn = viewModel.uiState.value.vpn
        assertTrue(vpn.enabled)
        assertEquals("pais.ovpn", vpn.fileName)
        assertEquals("vpnuser", vpn.username)
        assertEquals(goodOvpn, vpn.ovpn)
        assertEquals("vpnpass", vpn.password)
        assertNotNull(vpn.needsCredentials)
    }

    @Test
    fun `starting for another profile forgets the VPN of the previous one`() {
        val viewModel = newViewModel()
        viewModel.loadOvpnFile("pais.ovpn", goodOvpn)

        viewModel.start("otro")

        assertFalse(viewModel.uiState.value.vpn.enabled)
    }

    /** Guarda de forma síncrona: con el despachador de pruebas sin cola, `save()` termina antes de volver. */
    private fun runTestSave(block: (ProfileEditorViewModel) -> Unit) = block(newViewModel())
}
