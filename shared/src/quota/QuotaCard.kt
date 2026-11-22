package org.uci.proxy.quota

import org.uci.proxy.core.model.UciQuota
import org.uci.proxy.core.quota.QuotaProblem
import org.uci.proxy.core.quota.QuotaStatus
import org.uci.proxy.core.quota.mbText
import org.uci.proxy.core.quota.percentText
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
import org.uci.proxy.core.model.QuotaSettings
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
    /** «hace 3 min», o null si no se sabe cuándo. */
    val checkedAgo: String? = null,
)

/** Verde hasta el 75 %, ámbar hasta el 90 %, rojo después: el mismo criterio en todas partes. */
val UciQuota.tone: UciTone
    get() = when {
        percent >= QuotaSettings.PAUSE_THRESHOLD -> UciTone.Danger
        percent >= 75 -> UciTone.Warning
        else -> UciTone.Success
    }

/** En MB, como los da la UCI («2000 MB»), sin pasar a GB: así coincide con lo que dice el servicio. */
private fun mb(value: Double): String = mbText(value)

/**
 * La tarjeta «Cuota de la UCI» de Consumo: el anillo con lo consumido y lo que queda. No dice cuándo se renueva: la cuota
 * de la UCI no tiene fecha, lo consumido baja solo con el tiempo (y puede pasar del 100 %).
 */
@Composable
fun QuotaCard(ui: QuotaCardUi, onRefresh: () -> Unit, onEditProfile: () -> Unit, modifier: Modifier = Modifier) {
    val status = ui.status
    val c = ProxyUciTheme.colors
    UciCard(modifier.fillMaxWidth(), tone = status.quota?.tone?.takeIf { it != UciTone.Success } ?: UciTone.Neutral) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("CUOTA DE LA UCI", style = ProxyUciTheme.mono.badge, color = c.onSurfaceFaint)
                Text(status.account.orEmpty(), style = ProxyUciTheme.mono.code, color = c.onSurfaceMuted)
            }
            status.quota?.level?.takeIf { it.isNotBlank() }?.let { UciStatusPill(it) }
            UciIconButton(
                UciIcons.Refresh,
                if (status.checking) "Consultando la cuota" else "Consultar la cuota ahora",
                onRefresh,
                enabled = !status.checking,
                bordered = false,
            )
        }

        val quota = status.quota
        if (quota != null) {
            val color = quota.tone.colors().content
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UciQuotaRing(
                    fraction = (quota.percent / 100).toFloat(),
                    color = color,
                    description = "Usado el ${percentText(quota)} de la cuota; quedan ${mb(quota.remainingMb)}",
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(mb(quota.remainingMb), style = ProxyUciTheme.mono.metricLarge)
                        Text("disponibles", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
                    }
                }
                Text(
                    "${mb(quota.usedMb).removeSuffix(" MB")} de ${quota.assignedMb} MB usados · ${percentText(quota)}",
                    style = ProxyUciTheme.mono.metricSmall,
                    color = color,
                )
            }
            if (quota.usedMb > quota.assignedMb) {
                Text(
                    "Te pasaste ${mb(quota.usedMb - quota.assignedMb)} de tu cuota.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (status.checking) {
            Text("Consultando la cuota…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceMuted)
        }

        if (status.paused) {
            UciInlineAlert(
                "Llegaste al ${QuotaSettings.PAUSE_THRESHOLD} % y el túnel se desconectó. Puedes conectar igual desde Conexión.",
                title = "Túnel en pausa por la cuota",
                tone = UciTone.Danger,
            )
        }
        status.problem?.let { problem ->
            UciInlineAlert(
                problemMessage(problem),
                title = if (quota == null) "No se pudo consultar la cuota" else "No se pudo actualizar",
                tone = if (problem == QuotaProblem.UNREACHABLE) UciTone.Warning else UciTone.Danger,
                action = if (problem == QuotaProblem.WRONG_CREDENTIALS) {
                    { UciButton("Editar perfil", onEditProfile, variant = UciButtonVariant.Text) }
                } else {
                    null
                },
            )
        }
        Text(
            "Es la cuota de toda tu cuenta: cuenta lo que navegas desde cualquier equipo. Lo consumido baja solo con el " +
                "tiempo, según la UCI." +
                (ui.checkedAgo?.let { " Consultada $it." } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceFaint,
        )
    }
}

internal fun problemMessage(problem: QuotaProblem): String = when (problem) {
    QuotaProblem.WRONG_CREDENTIALS -> "El servicio de cuotas no aceptó el usuario o la contraseña del perfil."
    QuotaProblem.UNREACHABLE -> "No se llegó al servicio de cuotas. Solo responde desde la red de la UCI; se reintenta solo."
    QuotaProblem.UNTRUSTED_CERTIFICATE -> "El certificado del servicio no se pudo verificar: lo firma una autoridad de la " +
        "UCI que el sistema no reconoce. Apaga «Verificar el certificado del servicio» en Ajustes."
    QuotaProblem.SERVICE_CHANGED -> "El servicio de cuotas contestó algo que esta versión no entiende."
}
