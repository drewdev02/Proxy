package org.uci.proxy.usage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.common.bytesText
import org.uci.proxy.common.formatBytes
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.traffic.NamedTraffic
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBar
import org.uci.proxy.ui.components.UciBarChart
import org.uci.proxy.ui.components.UciBarSegment
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciProportionBar
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciStatusDot
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons

/** La pantalla Consumo a nivel de pantalla: obtiene su ViewModel y pasa estado y acciones a la versión sin estado. */
@Composable
internal fun UsageScreen(onBack: () -> Unit, viewModel: UsageViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UsageScreen(state, onPeriodChange = viewModel::selectPeriod, onBack = onBack)
}

/**
 * Lo que pasó por el túnel: total y reparto por salida, cada día, y los destinos y apps que más consumieron. En escritorio,
 * dos columnas: el resumen y los días a la izquierda; destinos y apps a la derecha.
 *
 * @param wide null: se decide por el ancho disponible.
 */
@Composable
internal fun UsageScreen(
    state: UsageUiState,
    onPeriodChange: (UsagePeriod) -> Unit = {},
    onBack: () -> Unit = {},
    wide: Boolean? = null,
) {
    val spacing = ProxyUciTheme.spacing
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        UciTopBar(title = "Consumo", subtitle = "Lo que pasó por el túnel en este equipo", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = spacing.screenMargin, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UsagePeriod.entries.forEach { UciFilterChip(it.label, state.period == it, { onPeriodChange(it) }) }
        }

        if (state.loaded && state.total == 0L) {
            UciEmptyState(
                title = if (state.period == UsagePeriod.TODAY) "Hoy aún no hay consumo" else "Aún no hay consumo",
                icon = UciIcons.DataUsage,
                message = "Conecta y navega: aquí verás cada día lo que pasa por el proxy y por Otro País.",
                modifier = Modifier.padding(top = spacing.lg),
            )
            return@Column
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val twoColumns = wide ?: (maxWidth >= 840.dp)
            val summary: @Composable ColumnScope.() -> Unit = {
                SummaryCard(state)
                if (state.period != UsagePeriod.TODAY) DailyCard(state)
                Footnote()
            }
            val ranking: @Composable ColumnScope.() -> Unit = {
                RankingCard("Destinos", "Los sitios que más consumieron", state.hosts, nameOf = { it.ifEmpty { "Sin detalle (todo por Otro País)" } })
                if (state.apps.isNotEmpty()) AppsCard(state.apps)
            }
            if (twoColumns) {
                Row(
                    Modifier.widthIn(max = 1200.dp).fillMaxSize().padding(horizontal = spacing.screenMarginWide),
                    horizontalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    ScrollColumn(Modifier.weight(1f), summary)
                    ScrollColumn(Modifier.weight(1f), ranking)
                }
            } else {
                ScrollColumn(Modifier.fillMaxSize().padding(horizontal = spacing.screenMargin)) {
                    summary()
                    ranking()
                }
            }
        }
    }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = ProxyUciTheme.spacing.sm, bottom = ProxyUciTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md),
        content = content,
    )
}

@Composable
private fun SummaryCard(state: UsageUiState) {
    val c = ProxyUciTheme.colors
    UciCard(Modifier.fillMaxWidth()) {
        Text(
            when (state.period) {
                UsagePeriod.TODAY -> "HOY"
                UsagePeriod.WEEK -> "ÚLTIMOS 7 DÍAS"
                UsagePeriod.MONTH -> "ÚLTIMOS 30 DÍAS"
            },
            style = ProxyUciTheme.mono.badge,
            color = c.onSurfaceFaint,
        )
        val (value, unit) = formatBytes(state.total)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = ProxyUciTheme.mono.metricLarge)
            Text(unit, style = ProxyUciTheme.mono.badge, color = c.onSurfaceMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("↓ ${bytesText(state.down)} recibidos · ↑ ${bytesText(state.up)} enviados", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
        UciDivider()
        ExitShare(RouteExit.PROXY, state.proxy, state.total)
        if (state.country > 0) ExitShare(RouteExit.VPN, state.country, state.total)
    }
}

@Composable
private fun ExitShare(exit: RouteExit, bytes: Long, total: Long) {
    val color = exit.tone.colors().content
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UciStatusDot(exit.tone)
            Text(exit.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(bytesText(bytes), style = ProxyUciTheme.mono.metricSmall)
            Text(percent(bytes, total), style = ProxyUciTheme.mono.badge, color = color)
        }
        UciProportionBar(if (total > 0) bytes.toFloat() / total else 0f, color = color)
    }
}

@Composable
private fun DailyCard(state: UsageUiState) {
    val c = ProxyUciTheme.colors
    val proxyColor = RouteExit.PROXY.tone.colors().content
    val countryColor = RouteExit.VPN.tone.colors().content
    UciSectionHeader("Por día")
    UciCard(Modifier.fillMaxWidth()) {
        val many = state.days.size > 7
        UciBarChart(
            bars = state.days.mapIndexed { index, day ->
                UciBar(
                    // Con 30 días no caben todas las etiquetas: una por semana, contando hacia atrás desde hoy.
                    label = if (!many) weekdayOf(day.day) else if ((state.days.lastIndex - index) % 7 == 0) dayOfMonth(day.day) else "",
                    segments = listOf(UciBarSegment(day.proxy.toFloat(), proxyColor), UciBarSegment(day.country.toFloat(), countryColor)),
                    highlighted = day.day == state.today,
                )
            },
            description = "Consumo de cada día del periodo; hoy, ${bytesText(state.days.lastOrNull()?.total ?: 0)}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(RouteExit.PROXY)
            if (state.country > 0) Legend(RouteExit.VPN)
            Text(
                "Hoy: ${bytesText(state.days.lastOrNull()?.total ?: 0)}",
                style = ProxyUciTheme.mono.badge,
                color = c.onSurfaceMuted,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun Legend(exit: RouteExit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UciStatusDot(exit.tone)
        Text(exit.label, style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
    }
}

@Composable
private fun AppsCard(apps: List<NamedTraffic>) {
    // Los nombres de las apps, si el sistema los da (Android); si no, el paquete.
    val catalog = LocalAppCatalog.current
    val labels by produceState(emptyMap<String, String>(), catalog) {
        value = catalog?.installedApps()?.associate { it.packageName to it.label }.orEmpty()
    }
    RankingCard("Apps", "Lo que abrieron por el proxy", apps, nameOf = { labels[it] ?: it })
}

@Composable
private fun RankingCard(title: String, subtitle: String, items: List<NamedTraffic>, nameOf: (String) -> String) {
    val c = ProxyUciTheme.colors
    UciSectionHeader(title)
    UciCard(Modifier.fillMaxWidth()) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
        val top = items.firstOrNull()?.total ?: 0
        items.forEachIndexed { index, item ->
            if (index > 0) UciDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nameOf(item.name), style = ProxyUciTheme.mono.code, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(bytesText(item.total), style = ProxyUciTheme.mono.metricSmall)
                }
                // La barra compara con el primero, para que se vea la diferencia aunque haya muchos destinos pequeños.
                UciProportionBar(if (top > 0) item.total.toFloat() / top else 0f)
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        "Se cuenta lo que pasa por el túnel en este equipo: lo que va por el proxy y por Otro País. Lo que sale directo " +
            "(la red local, las reglas «Directo») no pasa por él y no se cuenta. Se guardan los últimos 90 días.",
        style = MaterialTheme.typography.bodySmall,
        color = ProxyUciTheme.colors.onSurfaceFaint,
    )
}

private fun percent(part: Long, total: Long): String = if (total <= 0) "0 %" else "${(part * 100 + total / 2) / total} %"

/** «Lu», «Ma»… del día [epochDay]. */
internal fun weekdayOf(epochDay: Int): String = when (LocalDate.fromEpochDays(epochDay).dayOfWeek) {
    DayOfWeek.MONDAY -> "Lu"
    DayOfWeek.TUESDAY -> "Ma"
    DayOfWeek.WEDNESDAY -> "Mi"
    DayOfWeek.THURSDAY -> "Ju"
    DayOfWeek.FRIDAY -> "Vi"
    DayOfWeek.SATURDAY -> "Sá"
    DayOfWeek.SUNDAY -> "Do"
}

private fun dayOfMonth(epochDay: Int): String = LocalDate.fromEpochDays(epochDay).day.toString()

@Preview
@Composable
private fun UsageScreenPreview() = ProxyUciTheme {
    UsageScreen(
        UsageUiState(
            today = 20_000,
            days = (19_994..20_000).map { DayUsage(it, proxy = (it % 5 + 1) * 80_000_000L, country = (it % 3) * 30_000_000L) },
            up = 120_000_000,
            down = 1_300_000_000,
            hosts = listOf(NamedTraffic("youtube.com", 1_000, 600_000_000), NamedTraffic("github.com", 2_000, 90_000_000)),
            loaded = true,
        ),
    )
}
