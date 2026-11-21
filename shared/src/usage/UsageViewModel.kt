package org.uci.proxy.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.traffic.DailyTraffic
import org.uci.proxy.core.traffic.NamedTraffic
import org.uci.proxy.core.traffic.TrafficHistory
import org.uci.proxy.core.traffic.TrafficRecorder

/** Qué días se suman en la pantalla. */
enum class UsagePeriod(val label: String, val days: Int) {
    TODAY("Hoy", 1),
    WEEK("7 días", 7),
    MONTH("30 días", 30),
}

/** Lo de un día, por salida. [day] es el día local contado desde el 1 de enero de 1970. */
data class DayUsage(val day: Int, val proxy: Long, val country: Long) {
    val total: Long get() = proxy + country
}

data class UsageUiState(
    val period: UsagePeriod = UsagePeriod.WEEK,
    /** Hoy, para resaltarlo en la gráfica. */
    val today: Int = 0,
    /** Un elemento por cada día del periodo, del más antiguo a hoy; los días sin tráfico van con cero. */
    val days: List<DayUsage> = emptyList(),
    val up: Long = 0,
    val down: Long = 0,
    /** Los destinos que más consumieron, de más a menos. */
    val hosts: List<NamedTraffic> = emptyList(),
    /** Las apps que más consumieron; vacío donde el sistema no dice qué app abre cada conexión. */
    val apps: List<NamedTraffic> = emptyList(),
    /** False hasta que llega la primera lectura de la base: así no se muestra «sin consumo» mientras carga. */
    val loaded: Boolean = false,
) {
    val proxy: Long get() = days.sumOf { it.proxy }
    val country: Long get() = days.sumOf { it.country }
    val total: Long get() = up + down
}

/**
 * El consumo guardado: lo que pasó por el túnel cada día, por salida, por destino y por app. Se actualiza solo cuando se
 * guarda lo nuevo (cada pocos segundos mientras hay tráfico).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    private val history: TrafficHistory,
    private val recorder: TrafficRecorder,
) : ViewModel() {
    private val period = MutableStateFlow(UsagePeriod.WEEK)

    init {
        // Lo medido en los últimos segundos aún no está en la base: se guarda ya para verlo al abrir.
        viewModelScope.launch { recorder.saveNow() }
    }

    val uiState: StateFlow<UsageUiState> = period.flatMapLatest { chosen ->
        val today = recorder.today()
        val from = today - chosen.days + 1
        combine(history.daily(from), history.topHosts(from, TOP), history.topApps(from, TOP)) { daily, hosts, apps ->
            buildState(chosen, today, from, daily, hosts, apps)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState(period = period.value))

    fun selectPeriod(chosen: UsagePeriod) {
        period.value = chosen
    }

    private companion object {
        const val TOP = 8
    }
}

internal fun buildState(
    period: UsagePeriod,
    today: Int,
    from: Int,
    daily: List<DailyTraffic>,
    hosts: List<NamedTraffic>,
    apps: List<NamedTraffic>,
): UsageUiState {
    val byDay = daily.groupBy { it.day }
    return UsageUiState(
        period = period,
        today = today,
        days = (from..today).map { day ->
            val rows = byDay[day].orEmpty()
            DayUsage(day, proxy = rows.filter { it.exit == RouteExit.PROXY }.sumOf { it.total }, country = rows.filter { it.exit == RouteExit.VPN }.sumOf { it.total })
        },
        up = daily.sumOf { it.up },
        down = daily.sumOf { it.down },
        hosts = hosts,
        apps = apps,
        loaded = true,
    )
}
