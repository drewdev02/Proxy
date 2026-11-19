package org.uci.proxy.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.RuleKind
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.ActiveProfileStore
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.resolveActiveProfileId
import org.uci.proxy.core.proxy.ProxyTestResult
import org.uci.proxy.core.proxy.ProxyTester
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/** El resultado de probar el proxy del perfil activo desde «Conexión». */
sealed interface ProxyCheck {
    data object Idle : ProxyCheck

    data object Running : ProxyCheck

    data object LocalNetworkDenied : ProxyCheck

    /** [millis] es lo que tardó la prueba completa: conectar, autenticarse y abrir el túnel de prueba. */
    data class Finished(val result: ProxyTestResult, val millis: Long) : ProxyCheck
}

/**
 * Cómo sale el tráfico del perfil, contado para un resumen de una línea.
 * @param rules cuántas reglas hay de cada tipo para cada salida.
 */
data class RoutingSummary(
    val enabled: Boolean,
    val defaultExit: RouteExit,
    val hasVpn: Boolean,
    val rules: Map<RouteExit, Map<RuleKind, Int>> = emptyMap(),
)

fun ProxyProfile.routingSummary(): RoutingSummary = RoutingSummary(
    enabled = routing.enabled,
    defaultExit = if (routing.enabled) routing.defaultExit else if (countryExit != null) RouteExit.VPN else RouteExit.PROXY,
    hasVpn = countryExit != null,
    rules = if (!routing.enabled) {
        emptyMap()
    } else {
        routing.rules.groupBy { it.exit }.mapValues { (_, rules) -> rules.groupingBy { it.kind }.eachCount() }
    },
)

data class ConnectionUiState(
    /** False donde el túnel aún no existe (iOS): no se ofrece conectar. */
    val tunnelAvailable: Boolean = true,
    val profiles: List<ProxyProfile> = emptyList(),
    val activeProfile: ProxyProfile? = null,
    val tunnel: TunnelState = TunnelState.Disconnected,
    /**
     * Desde cuándo está conectado, o null si no se sabe: solo se cuenta cuando esta pantalla vio la conexión
     * completarse (si la app se abre con el túnel ya conectado, no se inventa una hora).
     */
    val connectedSince: TimeMark? = null,
    val check: ProxyCheck = ProxyCheck.Idle,
    /** Los últimos mensajes importantes del registro, el más reciente primero. */
    val recentActivity: List<LogEntry> = emptyList(),
) {
    /** Con el túnel ocupado con un perfil no se cambia de perfil: primero hay que desconectar. */
    val canChangeProfile: Boolean
        get() = tunnel is TunnelState.Disconnected || tunnel is TunnelState.Failed

    val routing: RoutingSummary? get() = activeProfile?.routingSummary()
}

/**
 * La pantalla principal: el perfil activo, el botón de conectar, la prueba del proxy, cómo sale el tráfico y la
 * actividad reciente. Solo muestra datos reales; no hay contadores de tráfico porque el motor no los mide.
 */
class ConnectionViewModel(
    private val profiles: ProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val control: TunnelControl,
    monitor: TunnelMonitor,
    private val proxyTester: ProxyTester,
    private val localNetworkPermission: LocalNetworkPermission,
    logBuffer: LogBuffer,
    private val timeSource: TimeSource,
) : ViewModel() {
    private val check = MutableStateFlow<ProxyCheck>(ProxyCheck.Idle)
    private val connectedSince = MutableStateFlow<TimeMark?>(null)

    init {
        // Se cuenta el tiempo conectado solo si se vio pasar de «conectando» a «conectado».
        var previous: TunnelState = monitor.state.value
        monitor.state.onEach { state ->
            when {
                state is TunnelState.Connected && previous is TunnelState.Connecting -> connectedSince.value = timeSource.markNow()
                state !is TunnelState.Connected -> connectedSince.value = null
            }
            previous = state
        }.launchIn(viewModelScope)
    }

    val uiState: StateFlow<ConnectionUiState> = combine(
        profiles.profiles,
        activeProfileStore.activeProfileId,
        monitor.state,
        combine(check, connectedSince, ::Pair),
        logBuffer.entries,
    ) { list, chosen, tunnel, (check, since), log ->
        val activeId = resolveActiveProfileId(list.map { it.id }, chosen, tunnel.profileIdOrNull())
        ConnectionUiState(
            tunnelAvailable = control.isAvailable,
            profiles = list,
            activeProfile = list.firstOrNull { it.id == activeId },
            tunnel = tunnel,
            connectedSince = since,
            check = check,
            recentActivity = log.asReversed().asSequence().filter { it.level >= LogLevel.INFO }.take(RECENT_ACTIVITY).toList(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionUiState(tunnelAvailable = control.isAvailable))

    fun selectProfile(id: String) {
        if (!uiState.value.canChangeProfile) return
        activeProfileStore.select(id)
        // El resultado anterior era de otro perfil.
        check.value = ProxyCheck.Idle
    }

    fun connect() {
        val id = uiState.value.activeProfile?.id ?: return
        viewModelScope.launch { control.connect(id) }
    }

    fun disconnect() {
        viewModelScope.launch { control.disconnect() }
    }

    fun testProxy() {
        val profile = uiState.value.activeProfile ?: return
        if (check.value == ProxyCheck.Running) return
        check.value = ProxyCheck.Running
        viewModelScope.launch {
            val outcome = if (!localNetworkPermission.request()) {
                ProxyCheck.LocalNetworkDenied
            } else {
                val password = profiles.password(profile.id)
                val (result, took) = timeSource.measureTimedValue { proxyTester.test(profile, password) }
                ProxyCheck.Finished(result, took.inWholeMilliseconds)
            }
            // Si mientras tanto se eligió otro perfil, este resultado ya no le corresponde.
            check.update { if (it == ProxyCheck.Running && uiState.value.activeProfile?.id == profile.id) outcome else it }
        }
    }

    private companion object {
        const val RECENT_ACTIVITY = 4
    }
}

private fun TunnelState.profileIdOrNull(): String? = when (this) {
    is TunnelState.Connecting -> profileId
    is TunnelState.Connected -> profileId
    else -> null
}
