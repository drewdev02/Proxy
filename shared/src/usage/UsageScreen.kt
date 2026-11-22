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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.apps.LocalAppCatalog
import org.uci.proxy.common.bytesText
import org.uci.proxy.common.decimalSeparator
import org.uci.proxy.common.formatBytes
import org.uci.proxy.common.percentText
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.NamedTraffic
import org.uci.proxy.profile.label
import org.uci.proxy.profile.tone
import org.uci.proxy.quota.QuotaCard
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
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
internal fun UsageScreen(onBack: () -> Unit, onEditProfile: (String) -> Unit, viewModel: UsageViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UsageScreen(
        state,
        onPeriodChange = viewModel::selectPeriod,
        onBack = onBack,
        onRefreshQuota = viewModel::refreshQuota,
        onEditProfile = { state.quota.status.profileId?.let(onEditProfile) },
    )
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
    onRefreshQuota: () -> Unit = {},
    onEditProfile: () -> Unit = {},
) {
    val spacing = ProxyUciTheme.spacing
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        UciTopBar(title = stringResource(Res.string.usage_title), subtitle = stringResource(Res.string.usage_subtitle), onBack = onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = spacing.screenMargin, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UsagePeriod.entries.forEach { UciFilterChip(stringResource(it.label), state.period == it, { onPeriodChange(it) }) }
        }

        val quotaCard: @Composable () -> Unit = {
            if (state.quota.status.enabled) QuotaCard(state.quota, onRefreshQuota, onEditProfile)
        }
        if (state.loaded && state.total == 0L) {
            if (state.quota.status.enabled) {
                Column(Modifier.padding(horizontal = spacing.screenMargin, vertical = spacing.sm).widthIn(max = 720.dp)) { quotaCard() }
            }
            UciEmptyState(
                title = stringResource(if (state.period == UsagePeriod.TODAY) Res.string.usage_empty_today else Res.string.usage_empty),
                icon = UciIcons.DataUsage,
                message = stringResource(Res.string.usage_empty_message),
                modifier = Modifier.padding(top = spacing.lg),
            )
            return@Column
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val twoColumns = wide ?: (maxWidth >= 840.dp)
            val summary: @Composable ColumnScope.() -> Unit = {
                quotaCard()
                if (state.quota.status.enabled) UciSectionHeader(stringResource(Res.string.usage_on_this_device))
                SummaryCard(state)
                if (state.period != UsagePeriod.TODAY) DailyCard(state)
                Footnote()
            }
            val ranking: @Composable ColumnScope.() -> Unit = {
                val unknownHost = stringResource(Res.string.usage_hosts_unknown)
                RankingCard(
                    stringResource(Res.string.usage_hosts_title),
                    stringResource(Res.string.usage_hosts_subtitle),
                    state.hosts,
                    nameOf = { it.ifEmpty { unknownHost } },
                )
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
    val separator = decimalSeparator()
    UciCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                when (state.period) {
                    UsagePeriod.TODAY -> Res.string.usage_summary_today
                    UsagePeriod.WEEK -> Res.string.usage_summary_week
                    UsagePeriod.MONTH -> Res.string.usage_summary_month
                },
            ),
            style = ProxyUciTheme.mono.badge,
            color = c.onSurfaceFaint,
        )
        val (value, unit) = formatBytes(state.total, separator)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = ProxyUciTheme.mono.metricLarge)
            Text(unit, style = ProxyUciTheme.mono.badge, color = c.onSurfaceMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(stringResource(Res.string.usage_down_up, bytesText(state.down, separator), bytesText(state.up, separator)), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
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
            Text(bytesText(bytes, decimalSeparator()), style = ProxyUciTheme.mono.metricSmall)
            Text(percentText(percent(bytes, total)), style = ProxyUciTheme.mono.badge, color = color)
        }
        UciProportionBar(if (total > 0) bytes.toFloat() / total else 0f, color = color)
    }
}

@Composable
private fun DailyCard(state: UsageUiState) {
    val c = ProxyUciTheme.colors
    val proxyColor = RouteExit.PROXY.tone.colors().content
    val countryColor = RouteExit.VPN.tone.colors().content
    val todayText = bytesText(state.days.lastOrNull()?.total ?: 0, decimalSeparator())
    UciSectionHeader(stringResource(Res.string.usage_daily_title))
    UciCard(Modifier.fillMaxWidth()) {
        val many = state.days.size > 7
        UciBarChart(
            bars = state.days.mapIndexed { index, day ->
                UciBar(
                    // Con 30 días no caben todas las etiquetas: una por semana, contando hacia atrás desde hoy.
                    label = if (!many) stringResource(weekdayOf(day.day)) else if ((state.days.lastIndex - index) % 7 == 0) dayOfMonth(day.day) else "",
                    segments = listOf(UciBarSegment(day.proxy.toFloat(), proxyColor), UciBarSegment(day.country.toFloat(), countryColor)),
                    highlighted = day.day == state.today,
                )
            },
            description = stringResource(Res.string.usage_daily_description, todayText),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(RouteExit.PROXY)
            if (state.country > 0) Legend(RouteExit.VPN)
            Text(
                stringResource(Res.string.usage_daily_today, todayText),
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
    RankingCard(stringResource(Res.string.usage_apps_title), stringResource(Res.string.usage_apps_subtitle), apps, nameOf = { labels[it] ?: it })
}

@Composable
private fun RankingCard(title: String, subtitle: String, items: List<NamedTraffic>, nameOf: (String) -> String) {
    val c = ProxyUciTheme.colors
    val separator = decimalSeparator()
    UciSectionHeader(title)
    UciCard(Modifier.fillMaxWidth()) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted)
        val top = items.firstOrNull()?.total ?: 0
        items.forEachIndexed { index, item ->
            if (index > 0) UciDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nameOf(item.name), style = ProxyUciTheme.mono.code, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(bytesText(item.total, separator), style = ProxyUciTheme.mono.metricSmall)
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
        stringResource(Res.string.usage_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = ProxyUciTheme.colors.onSurfaceFaint,
    )
}

/** El porcentaje entero, redondeado, de [part] sobre [total]. */
private fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else ((part * 100 + total / 2) / total).toInt()

/** El nombre del filtro de cada periodo. */
private val UsagePeriod.label: StringResource
    get() = when (this) {
        UsagePeriod.TODAY -> Res.string.usage_period_today
        UsagePeriod.WEEK -> Res.string.usage_period_week
        UsagePeriod.MONTH -> Res.string.usage_period_month
    }

/** «Lu», «Ma»… del día [epochDay]. */
internal fun weekdayOf(epochDay: Int): StringResource = when (LocalDate.fromEpochDays(epochDay).dayOfWeek) {
    DayOfWeek.MONDAY -> Res.string.usage_weekday_monday
    DayOfWeek.TUESDAY -> Res.string.usage_weekday_tuesday
    DayOfWeek.WEDNESDAY -> Res.string.usage_weekday_wednesday
    DayOfWeek.THURSDAY -> Res.string.usage_weekday_thursday
    DayOfWeek.FRIDAY -> Res.string.usage_weekday_friday
    DayOfWeek.SATURDAY -> Res.string.usage_weekday_saturday
    DayOfWeek.SUNDAY -> Res.string.usage_weekday_sunday
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
