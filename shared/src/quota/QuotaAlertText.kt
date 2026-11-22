package org.uci.proxy.quota

import org.uci.proxy.core.model.QuotaAlert
import org.uci.proxy.core.quota.mbText
import org.uci.proxy.core.quota.wholePercent
import org.jetbrains.compose.resources.getString
import org.uci.proxy.common.loadDecimalSeparator
import org.uci.proxy.common.loadPercentText
import org.uci.proxy.core.model.QuotaSettings
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*

/** El título de la notificación de [QuotaAlert]. */
suspend fun QuotaAlert.title(): String = when (this) {
    is QuotaAlert.Threshold -> getString(Res.string.quota_notification_threshold_title, loadPercentText(threshold))
    is QuotaAlert.Paused -> getString(Res.string.quota_notification_paused_title)
}

/** El texto de la notificación de [QuotaAlert]. */
suspend fun QuotaAlert.message(): String {
    val separator = loadDecimalSeparator()
    val percent = loadPercentText(quota.wholePercent)
    return when (this) {
        is QuotaAlert.Threshold -> {
            val used = mbText(quota.usedMb, separator)
            when {
                quota.usedMb > quota.assignedMb -> getString(
                    Res.string.quota_notification_threshold_over, percent, used, quota.assignedMb,
                    mbText(quota.usedMb - quota.assignedMb, separator),
                )
                threshold >= QuotaSettings.PAUSE_THRESHOLD -> getString(
                    Res.string.quota_notification_threshold_left, percent, used, quota.assignedMb, mbText(quota.remainingMb, separator),
                )
                else -> getString(Res.string.quota_notification_threshold, percent, used, quota.assignedMb)
            }
        }
        is QuotaAlert.Paused -> getString(Res.string.quota_notification_paused, percent)
    }
}
