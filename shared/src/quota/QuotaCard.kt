package org.uci.proxy.quota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.common.decimalSeparator
import org.uci.proxy.common.percentText
import org.uci.proxy.core.settings.QuotaSettings
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciQuotaRing
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons

/**
 * La cuota tal como se dibuja, con lo que depende de la hora ya calculado: así la tarjeta no lee el reloj y sus capturas
 * no cambian con el tiempo.
 */
data class QuotaCardUi(
    val status: QuotaStatus,
    /** Cuánto hace que se consultó, o null si no se sabe. */
    val checkedAgo: Duration? = null,
)

/** Verde hasta el 75 %, ámbar hasta el 90 %, rojo después: el mismo criterio en todas partes. */
val UciQuota.tone: UciTone
    get() = when {
        percent >= QuotaSettings.PAUSE_THRESHOLD -> UciTone.Danger
        percent >= 75 -> UciTone.Warning
        else -> UciTone.Success
    }


/**
 * La tarjeta «Cuota de la UCI» de Consumo: el anillo con lo consumido y lo que queda. No dice cuándo se renueva: la cuota
 * de la UCI no tiene fecha, lo consumido baja solo con el tiempo (y puede pasar del 100 %).
 */
@Composable
fun QuotaCard(ui: QuotaCardUi, onRefresh: () -> Unit, onEditProfile: () -> Unit, modifier: Modifier = Modifier) {
    val status = ui.status
    val c = ProxyUciTheme.colors
    // En MB, como los da la UCI («2000 MB»), sin pasar a GB: así coincide con lo que dice el servicio.
    val separator = decimalSeparator()
    fun mb(value: Double): String = mbText(value, separator)
    UciCard(modifier.fillMaxWidth(), tone = status.quota?.tone?.takeIf { it != UciTone.Success } ?: UciTone.Neutral) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.quota_card_title), style = ProxyUciTheme.mono.badge, color = c.onSurfaceFaint)
                Text(status.account.orEmpty(), style = ProxyUciTheme.mono.code, color = c.onSurfaceMuted)
            }
            status.quota?.level?.takeIf { it.isNotBlank() }?.let { UciStatusPill(it) }
            UciIconButton(
                UciIcons.Refresh,
                stringResource(if (status.checking) Res.string.quota_checking_now else Res.string.quota_check_now),
                onRefresh,
                enabled = !status.checking,
                bordered = false,
            )
        }

        val quota = status.quota
        if (quota != null) {
            val color = quota.tone.colors().content
            val percent = percentText(quota.wholePercent)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UciQuotaRing(
                    fraction = (quota.percent / 100).toFloat(),
                    color = color,
                    description = stringResource(Res.string.quota_ring_description, percent, mb(quota.remainingMb)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(mb(quota.remainingMb), style = ProxyUciTheme.mono.metricLarge)
                        Text(stringResource(Res.string.quota_available), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
                    }
                }
                Text(
                    stringResource(Res.string.quota_used_of, mb(quota.usedMb).removeSuffix(" MB"), quota.assignedMb, percent),
                    style = ProxyUciTheme.mono.metricSmall,
                    color = color,
                )
            }
            if (quota.usedMb > quota.assignedMb) {
                Text(
                    stringResource(Res.string.quota_over, mb(quota.usedMb - quota.assignedMb)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (status.checking) {
            Text(stringResource(Res.string.quota_checking), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceMuted)
        }

        if (status.paused) {
            UciInlineAlert(
                stringResource(Res.string.quota_paused_message, QuotaSettings.PAUSE_THRESHOLD),
                title = stringResource(Res.string.quota_paused_title),
                tone = UciTone.Danger,
            )
        }
        status.problem?.let { problem ->
            UciInlineAlert(
                stringResource(problem.message),
                title = stringResource(if (quota == null) Res.string.quota_problem_title_first else Res.string.quota_problem_title_update),
                tone = if (problem == QuotaProblem.UNREACHABLE) UciTone.Warning else UciTone.Danger,
                action = if (problem == QuotaProblem.WRONG_CREDENTIALS) {
                    { UciButton(stringResource(Res.string.quota_edit_profile), onEditProfile, variant = UciButtonVariant.Text) }
                } else {
                    null
                },
            )
        }
        Text(
            listOfNotNull(stringResource(Res.string.quota_footnote), ui.checkedAgo?.let { checkedAgoText(it) }).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceFaint,
        )
    }
}

/** Por qué no se pudo consultar la cuota, como frase completa. */
internal val QuotaProblem.message: StringResource
    get() = when (this) {
        QuotaProblem.WRONG_CREDENTIALS -> Res.string.quota_problem_wrong_credentials
        QuotaProblem.UNREACHABLE -> Res.string.quota_problem_unreachable
        QuotaProblem.UNTRUSTED_CERTIFICATE -> Res.string.quota_problem_untrusted_certificate
        QuotaProblem.SERVICE_CHANGED -> Res.string.quota_problem_service_changed
    }

/** Cuándo se consultó, con la unidad más grande que quepa entera. */
sealed interface CheckedAgo {
    data object Now : CheckedAgo

    data class Minutes(val count: Int) : CheckedAgo

    data class Hours(val count: Int) : CheckedAgo

    data class Days(val count: Int) : CheckedAgo
}

/** «ahora mismo», o en minutos, horas o días, sin decimales. */
internal fun checkedAgo(elapsed: Duration): CheckedAgo {
    val minutes = elapsed.inWholeMinutes.toInt()
    return when {
        minutes < 1 -> CheckedAgo.Now
        minutes < 60 -> CheckedAgo.Minutes(minutes)
        minutes < 24 * 60 -> CheckedAgo.Hours(minutes / 60)
        else -> CheckedAgo.Days(minutes / (24 * 60))
    }
}

/** «Consultada hace 3 min.» */
@Composable
private fun checkedAgoText(elapsed: Duration): String = when (val ago = checkedAgo(elapsed)) {
    CheckedAgo.Now -> stringResource(Res.string.quota_checked_now)
    is CheckedAgo.Minutes -> pluralStringResource(Res.plurals.quota_checked_minutes, ago.count, ago.count)
    is CheckedAgo.Hours -> pluralStringResource(Res.plurals.quota_checked_hours, ago.count, ago.count)
    is CheckedAgo.Days -> pluralStringResource(Res.plurals.quota_checked_days, ago.count, ago.count)
}
