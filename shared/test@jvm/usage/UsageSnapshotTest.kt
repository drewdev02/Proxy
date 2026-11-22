package org.uci.proxy.usage

import org.uci.proxy.core.model.NamedTraffic
import org.uci.proxy.quota.QuotaCardUi
import org.uci.proxy.core.quota.QuotaProblem
import org.uci.proxy.core.quota.QuotaStatus
import org.uci.proxy.core.model.UciQuota
import org.uci.proxy.ui.Snapshots
import kotlin.test.Test

/** La pantalla Consumo en móvil y en escritorio, contra sus capturas de referencia. */
class UsageSnapshotTest {
    // Un día fijo (miércoles 19 de noviembre de 2025) para que las etiquetas de los días no cambien con la fecha.
    private val today = 20_411

    private val week = UsageUiState(
        period = UsagePeriod.WEEK,
        today = today,
        days = listOf(
            DayUsage(today - 6, proxy = 310_000_000, country = 120_000_000),
            DayUsage(today - 5, proxy = 420_000_000, country = 180_000_000),
            DayUsage(today - 4, proxy = 380_000_000, country = 160_000_000),
            DayUsage(today - 3, proxy = 150_000_000, country = 40_000_000),
            DayUsage(today - 2, proxy = 0, country = 0),
            DayUsage(today - 1, proxy = 360_000_000, country = 210_000_000),
            DayUsage(today, proxy = 280_000_000, country = 100_000_000),
        ),
        up = 260_000_000,
        down = 2_450_000_000,
        hosts = listOf(
            NamedTraffic("netflix.com", 12_000_000, 790_000_000),
            NamedTraffic("youtube.com", 9_000_000, 610_000_000),
            NamedTraffic("github.com", 40_000_000, 210_000_000),
            NamedTraffic("web.whatsapp.com", 30_000_000, 95_000_000),
            NamedTraffic("eva.uci.cu", 4_000_000, 61_000_000),
        ),
        apps = listOf(
            NamedTraffic("com.google.android.youtube", 9_000_000, 610_000_000),
            NamedTraffic("com.whatsapp", 30_000_000, 95_000_000),
        ),
        loaded = true,
    )

    @Test
    fun usageScreenMatchesReferences() {
        val snapshots = Snapshots("usage")
        snapshots.check("phone-week", widthDp = 412, heightDp = 1350) { UsageScreen(week, wide = false) }
        snapshots.check("phone-month", widthDp = 412, heightDp = 900) {
            val month = week.copy(
                period = UsagePeriod.MONTH,
                days = (today - 29..today).map { DayUsage(it, proxy = ((it * 37) % 9 + 1) * 40_000_000L, country = ((it * 11) % 4) * 30_000_000L) },
            )
            UsageScreen(month, wide = false)
        }
        snapshots.check("phone-empty", widthDp = 412, heightDp = 700) {
            UsageScreen(UsageUiState(period = UsagePeriod.TODAY, today = today, loaded = true), wide = false)
        }
        snapshots.check("desktop-week", widthDp = 1100, heightDp = 900) { UsageScreen(week, wide = true) }

        val quota = UciQuota(assignedMb = 2048, usedMb = 1454.2, level = "Profesores", checkedAtMillis = 0)
        val status = QuotaStatus(enabled = true, profileId = "p1", account = "ana.perez", quota = quota)
        snapshots.check("phone-quota", widthDp = 412, heightDp = 1500) {
            UsageScreen(week.copy(quota = QuotaCardUi(status, checkedAgo = "hace 3 min")), wide = false)
        }
        snapshots.check("phone-quota-paused", widthDp = 412, heightDp = 1100) {
            // Pasada del 100 %: la cuota de la UCI lo permite.
            val paused = status.copy(quota = quota.copy(usedMb = 2310.0), paused = true, problem = QuotaProblem.UNREACHABLE)
            UsageScreen(week.copy(quota = QuotaCardUi(paused, checkedAgo = "hace 2 h")), wide = false)
        }
        snapshots.check("phone-quota-wrong-password", widthDp = 412, heightDp = 900) {
            val wrong = QuotaStatus(enabled = true, profileId = "p1", account = "ana.perez", problem = QuotaProblem.WRONG_CREDENTIALS)
            UsageScreen(UsageUiState(period = UsagePeriod.TODAY, today = today, loaded = true, quota = QuotaCardUi(wrong)), wide = false)
        }
        snapshots.check("desktop-quota", widthDp = 1100, heightDp = 1000) {
            UsageScreen(week.copy(quota = QuotaCardUi(status, checkedAgo = "hace 3 min")), wide = true)
        }
        snapshots.assertAllMatch()
    }
}
