package org.uci.proxy.logs

import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test

/** El registro en sus estados principales, contra sus capturas de referencia (ver [Snapshots]). */
class LogsSnapshotTest {
    private val base = 1_700_000_000_000

    private val entries = listOf(
        LogEntry(1, base, LogLevel.INFO, "ProxyUCI", "ProxyUCI en Android 16"),
        LogEntry(2, base + 1_000, LogLevel.INFO, "Tunnel", "Túnel activo en 10.9.0.1, proxy 10.0.2.2, con reglas de salida"),
        LogEntry(3, base + 1_200, LogLevel.DEBUG, "Dns", "DNS tipo 1: example.com"),
        LogEntry(4, base + 1_400, LogLevel.DEBUG, "Rutas", "App com.android.chrome: vpn hacia example.com"),
        LogEntry(5, base + 2_000, LogLevel.WARN, "Vpn", "El proxy no abrió 149.22.82.28:443: Unreachable(detail=Connection refused)"),
        LogEntry(6, base + 2_500, LogLevel.INFO, "Vpn", "Conectando con el servidor VPN 149.22.82.28:8443 a través del proxy (vía 149-22-82-28.sslip.io)"),
        LogEntry(7, base + 3_000, LogLevel.ERROR, "Proxy", "407: el proxy rechazó las credenciales"),
        LogEntry(8, base + 3_500, LogLevel.INFO, "Relay", "Túnel abierto: github.com:443"),
    )

    @Test
    fun logsMatchReferences() {
        // La hora se muestra en la zona del equipo: UTC para que la captura sea igual en todos.
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val snapshots = Snapshots("logs")
            val states = mapOf(
                "phone-live" to LogsUiState(entries = entries, total = entries.size),
                "phone-paused-filtered" to LogsUiState(
                    entries = entries.filter { it.level >= LogLevel.WARN },
                    minLevel = LogLevel.WARN,
                    paused = true,
                    total = entries.size,
                ),
                "phone-empty" to LogsUiState(),
            )
            states.forEach { (name, state) ->
                snapshots.check(name, widthDp = 412, heightDp = 900) { render(state) }
            }
            snapshots.check("desktop-live", widthDp = 1100, heightDp = 640) { render(states.getValue("phone-live")) }
            snapshots.assertAllMatch()
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @androidx.compose.runtime.Composable
    private fun render(state: LogsUiState) =
        LogsScreen(state = state, onBack = null, onLevelChange = {}, onTogglePause = {}, onClear = {}, onCopy = {})
}
