package org.uci.proxy.connection

import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RouteRule
import org.uci.proxy.core.model.RoutingRules
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.model.VpnProfile
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** La pantalla Conexión en cada estado, en móvil y en escritorio, contra sus capturas de referencia. */
class ConnectionSnapshotTest {
    private val uci = ProxyProfile(
        id = "p1",
        name = "UCI Docente",
        host = "proxy.uci.cu",
        port = 8080,
        protocol = ProxyProtocol.HTTP,
        username = "ana.perez",
        vpn = VpnProfile("canada_tcp.ovpn", "vpnuser"),
        routing = RoutingRules(
            enabled = true,
            defaultExit = RouteExit.PROXY,
            rules = listOf(
                RouteRule(RuleKind.DOMAIN, "netflix.com", RouteExit.VPN),
                RouteRule(RuleKind.DOMAIN, "hulu.com", RouteExit.VPN),
                RouteRule(RuleKind.APP, "com.whatsapp", RouteExit.DIRECT),
                RouteRule(RuleKind.DOMAIN, "ads.example", RouteExit.BLOCK),
            ),
        ),
    )
    private val home = ProxyProfile("p2", "Residencia", "10.0.0.1", 3128, ProxyProtocol.SOCKS5, "")

    private val activity = listOf(
        LogEntry(4, 1_700_000_000_000, LogLevel.INFO, "Relay", "github.com:443 abierto por el proxy"),
        LogEntry(3, 1_699_999_990_000, LogLevel.WARN, "Dns", "nip.io no respondió; se usa sslip.io"),
        LogEntry(2, 1_699_999_980_000, LogLevel.INFO, "Tunnel", "Túnel listo con el perfil UCI Docente"),
    )

    private val base = ConnectionUiState(profiles = listOf(uci, home), activeProfile = uci, recentActivity = activity)

    private fun connectedSince(): kotlin.time.TimeMark {
        val clock = TestTimeSource()
        val mark = clock.markNow()
        clock += 1.hours + 23.minutes + 45.seconds
        return mark
    }

    @Test
    fun connectionScreenMatchesReferences() {
        // La hora de la actividad se muestra en la zona del equipo: UTC para que la captura sea igual en todos.
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            renderAll()
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    private fun renderAll() {
        val snapshots = Snapshots("connection")
        val states = mapOf(
            "disconnected" to base,
            "connecting" to base.copy(tunnel = TunnelState.Connecting("p1"), check = ProxyCheck.Running),
            "connected" to base.copy(
                tunnel = TunnelState.Connected("p1"),
                connectedSince = connectedSince(),
                check = ProxyCheck.Finished(ProxyTestResult.Success, 184),
            ),
            "vpn-down" to base.copy(tunnel = TunnelState.Connected("p1", vpnDown = true)),
            "failed" to base.copy(tunnel = TunnelState.Failed(TunnelFailure.PROXY_AUTH_FAILED)),
            "no-profiles" to ConnectionUiState(),
            "no-tunnel" to base.copy(tunnelAvailable = false, profiles = listOf(home), activeProfile = home, recentActivity = emptyList()),
        )
        states.forEach { (name, state) ->
            snapshots.check("phone-$name", widthDp = 412, heightDp = 1500) { ConnectionScreen(state, wide = false) }
        }
        snapshots.check("desktop-connected", widthDp = 1100, heightDp = 760) {
            ConnectionScreen(states.getValue("connected"), wide = true)
        }
        snapshots.assertAllMatch()
    }
}
