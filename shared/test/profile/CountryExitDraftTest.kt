package org.uci.proxy.profile

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.OvpnConfigError
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.vpn.tailscale.TailscaleExitNode
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.error_exit_search_failed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** La lógica de «Otro País» del editor, sin ViewModel: funciones puras sobre los borradores de OpenVPN y Tailscale. */
class CountryExitDraftTest {
    private val keyHex = (0 until 256).joinToString("") { it.toString(16).padStart(2, '0') }
    private val goodOvpn = "client\nproto tcp\nremote 192.0.2.10 443\nauth-user-pass\n" +
        "<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n" +
        "<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\n${keyHex.chunked(32).joinToString("\n")}\n-----END OpenVPN Static key V1-----\n</tls-crypt>\n"
    private val noAuthOvpn = goodOvpn.replace("auth-user-pass\n", "")
    private val udpOvpn = goodOvpn.replace("proto tcp", "proto udp")

    private fun node(name: String) = TailscaleExitNode(id = name, name = name, online = true)

    // — OpenVPN —

    @Test
    fun `a valid file turns the exit on and remembers the file`() {
        val vpn = VpnDraft().withOvpnFile("pais.ovpn", goodOvpn)

        assertTrue(vpn.enabled)
        assertEquals("pais.ovpn", vpn.fileName)
        assertEquals(goodOvpn, vpn.ovpn)
        assertNull(vpn.fileError)
        assertTrue(vpn.needsCredentials)
    }

    @Test
    fun `a file without auth-user-pass does not ask for credentials`() {
        assertFalse(VpnDraft().withOvpnFile("pais.ovpn", noAuthOvpn).needsCredentials)
    }

    @Test
    fun `a file that cannot be used keeps its name and reason and does not turn the exit on`() {
        val vpn = VpnDraft(ovpn = "antiguo", fileName = "viejo.ovpn").withOvpnFile("malo.ovpn", udpOvpn)

        assertFalse(vpn.enabled)
        assertEquals("malo.ovpn", vpn.fileName)
        assertEquals("", vpn.ovpn)
        assertIs<OvpnConfigError>(vpn.fileError)
    }

    @Test
    fun `a good file after a bad one clears the reason`() {
        val vpn = VpnDraft().withOvpnFile("malo.ovpn", udpOvpn).withOvpnFile("bueno.ovpn", goodOvpn)

        assertNull(vpn.fileError)
        assertTrue(vpn.enabled)
    }

    @Test
    fun `OpenVPN needs a file and a username if the server asks`() {
        val on = VpnDraft(enabled = true)

        assertEquals(setOf(ProfileField.VPN_FILE, ProfileField.VPN_USERNAME), on.errors())
        assertEquals(setOf(ProfileField.VPN_USERNAME), on.withOvpnFile("a.ovpn", goodOvpn).errors())
        assertEquals(emptySet(), on.withOvpnFile("a.ovpn", goodOvpn).copy(username = "ana").errors())
        assertEquals(emptySet(), on.withOvpnFile("a.ovpn", noAuthOvpn).errors())
    }

    @Test
    fun `an exit that is off has no errors and nothing to save`() {
        val off = VpnDraft(enabled = false, tailscale = TailscaleDraft(authKey = ""))

        assertEquals(emptySet(), off.errors())
        assertNull(off.toExitAndSecrets())
    }

    @Test
    fun `OpenVPN is saved with the trimmed username and its secrets apart`() {
        val vpn = VpnDraft(enabled = true, fileName = "pais.ovpn", ovpn = goodOvpn, username = "  ana ", password = " clave ")

        val (exit, secrets) = assertNotNull(vpn.toExitAndSecrets())

        assertEquals(CountryExit.OpenVpn("pais.ovpn", "ana"), exit)
        assertEquals(CountryExitSecrets.OpenVpn(goodOvpn, " clave "), secrets)
    }

    // — Tailscale —

    @Test
    fun `Tailscale needs an http control address and a key`() {
        val on = VpnDraft(enabled = true, engine = CountryEngineKind.TAILSCALE)

        assertEquals(setOf(ProfileField.TAILSCALE_AUTH_KEY), on.errors())
        assertEquals(emptySet(), on.copy(tailscale = on.tailscale.copy(authKey = "tskey")).errors())
        assertEquals(
            setOf(ProfileField.TAILSCALE_CONTROL_URL, ProfileField.TAILSCALE_AUTH_KEY),
            on.copy(tailscale = on.tailscale.copy(controlUrl = "headscale.uci.cu")).errors(),
        )
        assertEquals(
            setOf(ProfileField.TAILSCALE_CONTROL_URL),
            on.copy(tailscale = on.tailscale.copy(controlUrl = "https://", authKey = "k")).errors(),
        )
    }

    @Test
    fun `the Tailscale errors ignore the OpenVPN fields and the other way round`() {
        val tailscaleOn = VpnDraft(enabled = true, engine = CountryEngineKind.TAILSCALE, tailscale = TailscaleDraft(authKey = "k"))
        val openVpnOn = VpnDraft(enabled = true, engine = CountryEngineKind.OPENVPN, tailscale = TailscaleDraft(authKey = ""))

        assertEquals(emptySet(), tailscaleOn.errors())
        assertEquals(setOf(ProfileField.VPN_FILE, ProfileField.VPN_USERNAME), openVpnOn.errors())
    }

    @Test
    fun `Tailscale is saved with a clean address and the default hostname when blank`() {
        val tailscale = TailscaleDraft(controlUrl = " https://hs.uci.cu/ ", authKey = " tskey ", exitNode = " ca-1 ", hostname = "  ")
        val vpn = VpnDraft(enabled = true, engine = CountryEngineKind.TAILSCALE, tailscale = tailscale)

        val (exit, secrets) = assertNotNull(vpn.toExitAndSecrets())

        assertEquals(CountryExit.Tailscale("https://hs.uci.cu", "ca-1", CountryExit.Tailscale.DEFAULT_HOSTNAME), exit)
        assertEquals(CountryExitSecrets.Tailscale("tskey"), secrets)
    }

    @Test
    fun `a single node found is picked when none was chosen`() {
        val running = TailscaleDraft(search = ExitNodeSearch.Running)

        val result = running.withSearchOutcome(ExitNodeSearch.Found(listOf(node("ca-1"))))

        assertEquals("ca-1", result.exitNode)
        assertIs<ExitNodeSearch.Found>(result.search)
    }

    @Test
    fun `a node that was already chosen is kept even if only one is found`() {
        val running = TailscaleDraft(exitNode = "us-9", search = ExitNodeSearch.Running)

        assertEquals("us-9", running.withSearchOutcome(ExitNodeSearch.Found(listOf(node("ca-1")))).exitNode)
    }

    @Test
    fun `with several nodes none is picked`() {
        val running = TailscaleDraft(search = ExitNodeSearch.Running)

        assertEquals("", running.withSearchOutcome(ExitNodeSearch.Found(listOf(node("ca-1"), node("us-1")))).exitNode)
    }

    @Test
    fun `a failed search is kept as the outcome and does not touch the chosen node`() {
        val running = TailscaleDraft(exitNode = "us-9", search = ExitNodeSearch.Running)
        val failed = ExitNodeSearch.Failed(Res.string.error_exit_search_failed)

        val result = running.withSearchOutcome(failed)

        assertEquals(failed, result.search)
        assertEquals("us-9", result.exitNode)
    }

    @Test
    fun `a result that arrives after the search was cancelled is ignored`() {
        val idle = TailscaleDraft(search = ExitNodeSearch.Idle)

        assertSame(idle, idle.withSearchOutcome(ExitNodeSearch.Found(listOf(node("ca-1")))))
    }

    // — Lo guardado del perfil —

    @Test
    fun `an OpenVPN exit becomes a draft with its file name and username`() {
        val vpn = VpnDraft.from(CountryExit.OpenVpn("pais.ovpn", "ana"))

        assertTrue(vpn.enabled)
        assertEquals(CountryEngineKind.OPENVPN, vpn.engine)
        assertEquals("pais.ovpn", vpn.fileName)
        assertEquals("ana", vpn.username)
        assertEquals("", vpn.ovpn)
    }

    @Test
    fun `a Tailscale exit becomes a draft with its settings but not its key`() {
        val vpn = VpnDraft.from(CountryExit.Tailscale("https://hs.uci.cu", "ca-1", "movil"))

        assertEquals(CountryEngineKind.TAILSCALE, vpn.engine)
        assertEquals(TailscaleDraft(controlUrl = "https://hs.uci.cu", exitNode = "ca-1", hostname = "movil"), vpn.tailscale)
    }

    @Test
    fun `the saved secrets complete the draft once they are read`() {
        val openVpn = VpnDraft.from(CountryExit.OpenVpn("pais.ovpn", "ana")).withSecrets(CountryExitSecrets.OpenVpn(noAuthOvpn, "clave"))
        val tailscale = VpnDraft.from(CountryExit.Tailscale("https://hs.uci.cu", "", "movil")).withSecrets(CountryExitSecrets.Tailscale("tskey"))

        assertEquals(noAuthOvpn, openVpn.ovpn)
        assertEquals("clave", openVpn.password)
        assertFalse(openVpn.needsCredentials)
        assertEquals("tskey", tailscale.tailscale.authKey)
    }

    @Test
    fun `secrets that could not be read leave the draft as it was`() {
        val vpn = VpnDraft.from(CountryExit.OpenVpn("pais.ovpn", "ana"))

        assertSame(vpn, vpn.withSecrets(null))
    }

    @Test
    fun `secrets with an unreadable file still ask for credentials`() {
        assertTrue(VpnDraft().withSecrets(CountryExitSecrets.OpenVpn("no es un ovpn", "")).needsCredentials)
    }
}
