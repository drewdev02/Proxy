package org.uci.proxy.profile

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.vpn.tailscale.TailscaleExitNode
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test

/** El editor de perfil en sus estados principales, contra sus capturas de referencia (ver [Snapshots]). */
class ProfileEditorSnapshotTest {
    private val draft = ProfileDraft(name = "UCI Docente", host = "proxy.uci.cu", port = "8080", protocol = ProxyProtocol.HTTP, username = "ana.perez")
    private val engines = listOf(CountryEngineKind.OPENVPN, CountryEngineKind.TAILSCALE)

    private val states = mapOf(
        "new-with-errors" to ProfileEditorUiState(
            isNew = true,
            draft = ProfileDraft(host = "http://proxy", port = "99999"),
            errors = setOf(ProfileField.NAME, ProfileField.HOST, ProfileField.PORT),
            engines = engines,
        ),
        "openvpn-rules" to ProfileEditorUiState(
            isNew = false,
            draft = draft,
            password = "secreto",
            test = ProxyTestUiState.Finished(ProxyTestResult.Success),
            engines = engines,
            vpn = VpnDraft(enabled = true, engine = CountryEngineKind.OPENVPN, fileName = "canada_tcp.ovpn", ovpn = "…", username = "vpnuser", password = "x"),
            routing = RoutingDraft(
                rules = RoutingRules(
                    enabled = true,
                    defaultExit = RouteExit.PROXY,
                    rules = listOf(
                        RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN),
                        RouteRule(RuleKind.IP, "10.0.0.0/8", RouteExit.DIRECT),
                        RouteRule(RuleKind.APP, "com.whatsapp", RouteExit.DIRECT),
                        RouteRule(RuleKind.DOMAIN, "ads.example", RouteExit.BLOCK),
                    ),
                ),
                newRule = "hulu.com",
            ),
        ),
        "tailscale-nodes" to ProfileEditorUiState(
            isNew = false,
            draft = draft,
            engines = engines,
            vpn = VpnDraft(
                enabled = true,
                engine = CountryEngineKind.TAILSCALE,
                tailscale = TailscaleDraft(
                    authKey = "tskey-auth-abc",
                    exitNode = "montreal",
                    search = ExitNodeSearch.Found(
                        listOf(
                            TailscaleExitNode(id = "1", name = "montreal", online = true, country = "Canadá", city = "Montreal"),
                            TailscaleExitNode(id = "2", name = "madrid", online = false, country = "España", city = "Madrid"),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun profileEditorMatchesReferences() {
        val snapshots = Snapshots("profile-editor")
        states.forEach { (name, state) ->
            snapshots.check("phone-$name", widthDp = 412, heightDp = 2300) {
                ProfileEditorScreen(
                    state = state,
                    onDraftChange = {},
                    onPasswordChange = {},
                    onTest = {},
                    onSave = {},
                    onCancel = {},
                    vpnAvailable = true,
                )
            }
        }
        snapshots.assertAllMatch()
    }
}
