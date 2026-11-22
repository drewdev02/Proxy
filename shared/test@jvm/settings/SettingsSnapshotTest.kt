package org.uci.proxy.settings

import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test
import org.uci.proxy.core.proxy.RefusalReason

/** Ajustes en sus estados principales, contra sus capturas de referencia (ver [Snapshots]). */
class SettingsSnapshotTest {
    private val settings = AppSettings(
        nameServices = IpNameService.BUILT_IN + IpNameService("custom", "Mi dominio", "{ip-}.mi-dominio.com", enabled = false),
    )

    // Resultados de prueba y no estados guardados: estos dicen «hace N min» con la hora real y la captura cambiaría cada día.
    private val tests = mapOf(
        "sslip" to ServiceTestUi.Done(NameServiceTestResult.Works),
        "nip" to ServiceTestUi.Done(NameServiceTestResult.Refused(RefusalReason.FORBIDDEN, "403 Forbidden")),
        "custom" to ServiceTestUi.Running,
    )

    @Test
    fun settingsMatchReferences() {
        val snapshots = Snapshots("settings")
        snapshots.check("phone-services", widthDp = 412, heightDp = 1900) {
            SettingsScreen(
                settings = settings,
                actions = SettingsActions(),
                tests = tests,
            )
        }
        snapshots.check("phone-names-off", widthDp = 412, heightDp = 900) {
            SettingsScreen(settings = settings.copy(nameByIp = false), actions = SettingsActions())
        }
        snapshots.check("desktop-services", widthDp = 1100, heightDp = 1500) {
            SettingsScreen(settings = settings, actions = SettingsActions(), tests = tests)
        }
        snapshots.assertAllMatch()
    }
}
