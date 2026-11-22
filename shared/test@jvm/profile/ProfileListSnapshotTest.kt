package org.uci.proxy.profile

import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test

/** La lista de perfiles en sus estados principales, contra sus capturas de referencia (ver [Snapshots]). */
class ProfileListSnapshotTest {
    private val profiles = listOf(
        ProxyProfile(
            "p1", "UCI Docente", "proxy.uci.cu", 8080, ProxyProtocol.HTTP, "ana.perez",
            countryExit = CountryExit.OpenVpn("canada_tcp.ovpn", "vpnuser"),
            routing = RoutingRules(enabled = true),
        ),
        ProxyProfile(
            "p2", "Residencia", "10.0.0.1", 3128, ProxyProtocol.HTTP, "ana.perez",
            countryExit = CountryExit.Tailscale(exitNode = "montreal"),
        ),
        ProxyProfile("p3", "Casa (SOCKS)", "192.168.1.10", 1080, ProxyProtocol.SOCKS5, ""),
    )

    private fun render(snapshots: Snapshots, name: String, profiles: List<ProxyProfile>, tunnel: TunnelState, deleteFailed: Boolean = false) {
        snapshots.check(name, widthDp = 412, heightDp = 900) {
            ProfileListScreen(
                profiles = profiles,
                platformLabel = "ProxyUCI en Android 16",
                deleteFailed = deleteFailed,
                tunnel = TunnelUiModel(isAvailable = true, state = tunnel),
                onAdd = {},
                onEdit = {},
                onDelete = {},
                onDismissDeleteError = {},
                onConnect = {},
                onDisconnect = {},
            )
        }
    }

    @Test
    fun profileListMatchesReferences() {
        val snapshots = Snapshots("profile-list")
        render(snapshots, "phone-connected", profiles, TunnelState.Connected("p1"))
        render(snapshots, "phone-empty", emptyList(), TunnelState.Disconnected)
        render(snapshots, "phone-errors", profiles, TunnelState.Failed(TunnelFailure.ProxyAuthFailed), deleteFailed = true)
        snapshots.assertAllMatch()
    }
}
