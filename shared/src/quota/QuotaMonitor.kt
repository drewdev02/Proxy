package org.uci.proxy.quota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.ActiveProfileStore
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.resolveActiveProfileId
import org.uci.proxy.core.settings.QuotaSettings
import org.uci.proxy.core.settings.SettingsRepository
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Por qué no se pudo consultar la cuota la última vez. */
enum class QuotaProblem { WRONG_CREDENTIALS, UNREACHABLE, UNTRUSTED_CERTIFICATE, SERVICE_CHANGED }

/** La cuota de la UCI del perfil activo, tal como la ve la interfaz. */
data class QuotaStatus(
    /** El perfil activo consulta la cuota (es de la UCI, o se activó en él). */
    val enabled: Boolean = false,
    val profileId: String? = null,
    val account: String? = null,
    /** La última cuota conocida de la cuenta, aunque sea de otra sesión. */
    val quota: UciQuota? = null,
    val checking: Boolean = false,
    /** Lo que falló en la última consulta; null si fue bien. */
    val problem: QuotaProblem? = null,
    /** La pausa por cuota está activa: se llegó al límite y este mes no se pidió seguir conectado. */
    val paused: Boolean = false,
)

/** Lo que la interfaz usa de la cuota: el estado, actualizar y «conectar igual». */
interface QuotaTracker {
    val status: StateFlow<QuotaStatus>

    /** Consulta ya, sin esperar a la siguiente vuelta. */
    fun refresh()

    /** Seguir conectado este mes aunque se haya llegado al límite de la pausa. */
    fun allowOverLimit()

    companion object {
        /** Sin cuota que seguir (vistas previas, pruebas). */
        val None = object : QuotaTracker {
            override val status: StateFlow<QuotaStatus> = MutableStateFlow(QuotaStatus())
            override fun refresh() = Unit
            override fun allowOverLimit() = Unit
        }
    }
}

/** Avisa a la persona fuera de la app (una notificación del sistema), donde la plataforma lo permite. */
fun interface QuotaNotifier {
    fun notify(title: String, message: String)

    companion object {
        /** Donde no hay notificaciones propias: el aviso queda en el registro y en la app. */
        val None = QuotaNotifier { _, _ -> }
    }
}

/**
 * Sigue la cuota de la UCI del perfil activo: la consulta al abrir la app y al conectar y, mientras el túnel está
 * conectado, cada [QuotaSettings.checkMinutes]; avisa al pasar cada umbral y, si se pidió, desconecta al llegar al límite.
 *
 * La cuota de la UCI no se renueva en una fecha: lo consumido baja solo con el tiempo (lo descuenta el servidor cada
 * poco, y no si está caído) y puede pasar del 100 %. Por eso los avisos y la pausa no van por meses: un aviso no se repite
 * mientras el consumo siga por encima de su umbral, y vuelve a estar listo cuando baja [REARM_MARGIN] puntos por debajo.
 *
 * Vive en el proceso de la interfaz, como los recorders: en escritorio el motor es otro proceso, y la consulta va directa
 * al servicio de la intranet (`*.uci.cu` sale sin proxy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotaMonitor(
    private val profiles: ProfileRepository,
    private val activeProfile: ActiveProfileStore,
    private val tunnel: TunnelMonitor,
    private val control: TunnelControl,
    private val settings: SettingsRepository,
    private val service: UciQuotaService,
    private val store: QuotaStore,
    private val notifier: QuotaNotifier,
    private val clock: Clock,
    dispatchers: AppDispatchers,
    private val logger: Logger,
) : QuotaTracker {
    /** Lo que decide qué consultar y cada cuánto. */
    private data class Target(val profile: ProxyProfile?, val connected: Boolean, val settings: QuotaSettings)

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.background)
    private val checkLock = Mutex()
    private val mutableStatus = MutableStateFlow(QuotaStatus())
    override val status: StateFlow<QuotaStatus> = mutableStatus.asStateFlow()

    private var current = Target(null, connected = false, settings = QuotaSettings())
    private var job: Job? = null

    /** Empieza a seguir la cuota. Llamarlo otra vez no hace nada. */
    fun start(): Job = job ?: scope.launch {
        combine(profiles.profiles, activeProfile.activeProfileId, tunnel.state, settings.settings.map { it.quota }) { list, chosen, state, quota ->
            val inTunnel = (state as? TunnelState.Connected)?.profileId ?: (state as? TunnelState.Connecting)?.profileId
            val id = resolveActiveProfileId(list.map { it.id }, chosen, inTunnel)
            Target(list.firstOrNull { it.id == id }, connected = state is TunnelState.Connected, settings = quota)
        }.distinctUntilChanged().collectLatest { target -> follow(target) }
    }.also { job = it }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Consulta ya, sin esperar a la siguiente vuelta (el botón de actualizar). */
    override fun refresh() {
        scope.launch { check(current) }
    }

    /** Seguir conectado aunque se haya llegado al límite de la pausa («Conectar igual»), hasta que el consumo baje. */
    override fun allowOverLimit() {
        val account = current.profile?.takeIf { it.checksUciQuota }?.uciAccount ?: return
        store.setOverLimitAllowed(account, true)
        mutableStatus.update { it.copy(paused = false) }
    }

    private suspend fun follow(target: Target) {
        current = target
        val profile = target.profile?.takeIf { it.checksUciQuota }
        if (profile == null) {
            mutableStatus.value = QuotaStatus(profileId = target.profile?.id)
            return
        }
        val account = profile.uciAccount
        val last = store.last(account)
        mutableStatus.update { now ->
            val same = now.account == account
            QuotaStatus(
                enabled = true,
                profileId = profile.id,
                account = account,
                quota = last,
                problem = if (same) now.problem else null,
                paused = last != null && isPaused(account, last, target.settings),
            )
        }
        // Recién consultada (al abrir y conectar casi a la vez): sin conexión no hace falta más; conectado, se espera.
        val sinceLast = last?.let { (clock.now().toEpochMilliseconds() - it.checkedAtMillis).milliseconds }
        if (sinceLast != null && sinceLast < MIN_GAP) {
            if (!target.connected) return
            delay(MIN_GAP - sinceLast)
        }
        // Sin conexión basta con una consulta (al abrir, al cambiar de perfil); conectado, se repite.
        var failures = 0
        while (true) {
            val problem = check(target)
            // Con la contraseña mal no se insiste: se vuelve a probar al cambiar el perfil o al pulsar actualizar.
            if (problem == QuotaProblem.WRONG_CREDENTIALS || !target.connected) return
            failures = if (problem == null) 0 else failures + 1
            val interval = target.settings.checkMinutes.coerceAtLeast(1).minutes
            // Si no se llega al servicio, se reintenta antes (1, 2, 4… minutos), sin pasar del intervalo normal.
            delay(if (failures == 0) interval else minOf(interval, (1 shl (failures - 1).coerceAtMost(5)).minutes))
        }
    }

    /** Consulta la cuota de [target] y actualiza el estado. @return lo que falló, o null si fue bien. */
    private suspend fun check(target: Target): QuotaProblem? = checkLock.withLock {
        val profile = target.profile?.takeIf { it.checksUciQuota } ?: return null
        val account = profile.uciAccount
        mutableStatus.update { if (it.account == account) it.copy(checking = true) else it }
        val result = service.fetch(
            account = account,
            password = profiles.password(profile.id),
            url = target.settings.serviceUrl?.takeIf { it.isNotBlank() } ?: QuotaSoap.SERVICE_URL,
            verifyCertificate = target.settings.verifyCertificate,
        )
        val problem = when (result) {
            is QuotaFetch.Success -> {
                val quota = UciQuota(result.assignedMb, result.usedMb, result.level, clock.now().toEpochMilliseconds())
                store.saveLast(account, quota)
                mutableStatus.update { if (it.account == account) it.copy(quota = quota, problem = null) else it }
                react(account, quota, target)
                null
            }
            is QuotaFetch.WrongCredentials -> QuotaProblem.WRONG_CREDENTIALS
            is QuotaFetch.Unreachable -> QuotaProblem.UNREACHABLE
            is QuotaFetch.UntrustedCertificate -> QuotaProblem.UNTRUSTED_CERTIFICATE
            is QuotaFetch.ServiceChanged -> QuotaProblem.SERVICE_CHANGED
        }
        if (problem != null) {
            val previous = mutableStatus.value.problem
            mutableStatus.update { if (it.account == account) it.copy(problem = problem) else it }
            // Una vez por problema, no en cada reintento.
            if (previous != problem) logger.log(LogLevel.WARN, TAG, "No se pudo consultar la cuota de $account: ${describe(problem)} (${detail(result)})")
        }
        mutableStatus.update { if (it.account == account) it.copy(checking = false) else it }
        problem
    }

    /** Avisa de los umbrales que se pasaron y, si toca, pausa. */
    private suspend fun react(account: String, quota: UciQuota, target: Target) {
        // Lo que bajó bastante por debajo de su umbral vuelve a estar listo para avisar; así, si oscila alrededor del
        // 75 %, no avisa una y otra vez.
        val stillAbove = store.alerted(account).filter { quota.percent > it - REARM_MARGIN }.toSet()
        val crossed = target.settings.alerts.filter { quota.percent >= it }.toSet()
        val fresh = crossed - stillAbove
        store.saveAlerted(account, stillAbove + crossed)
        if (quota.percent < QuotaSettings.PAUSE_THRESHOLD - REARM_MARGIN && store.overLimitAllowed(account)) {
            store.setOverLimitAllowed(account, false)
        }
        if (fresh.isNotEmpty()) {
            // Solo el más alto: si la primera consulta ya da un 92 %, un aviso, no tres.
            val top = fresh.max()
            val message = "Llevas el ${percentText(quota)} de tu cuota: ${mbText(quota.usedMb)} de ${quota.assignedMb} MB." +
                when {
                    quota.usedMb > quota.assignedMb -> " Te pasaste ${mbText(quota.usedMb - quota.assignedMb)}."
                    top >= QuotaSettings.PAUSE_THRESHOLD -> " Te quedan ${mbText(quota.remainingMb)}."
                    else -> ""
                }
            logger.log(if (top >= QuotaSettings.PAUSE_THRESHOLD) LogLevel.WARN else LogLevel.INFO, TAG, message)
            notifier.notify("Cuota de la UCI al $top %", message)
        }
        val pause = isPaused(account, quota, target.settings)
        mutableStatus.update { if (it.account == account) it.copy(paused = pause) else it }
        if (pause && tunnel.state.value is TunnelState.Connected) {
            val message = "Llegaste al ${percentText(quota)} de tu cuota: se desconectó el túnel para no agotarla. Puedes conectar igual desde Conexión."
            logger.log(LogLevel.WARN, TAG, message)
            notifier.notify("Túnel en pausa por la cuota", message)
            control.disconnect()
        }
    }

    private fun isPaused(account: String, quota: UciQuota, settings: QuotaSettings): Boolean =
        settings.pauseAtLimit && quota.percent >= QuotaSettings.PAUSE_THRESHOLD && !store.overLimitAllowed(account)

    private fun describe(problem: QuotaProblem) = when (problem) {
        QuotaProblem.WRONG_CREDENTIALS -> "el servicio no aceptó el usuario o la contraseña"
        QuotaProblem.UNREACHABLE -> "no se llegó al servicio (¿fuera de la red de la UCI?)"
        QuotaProblem.UNTRUSTED_CERTIFICATE -> "su certificado no se pudo verificar"
        QuotaProblem.SERVICE_CHANGED -> "contestó algo que no se entiende"
    }

    private fun detail(result: QuotaFetch) = when (result) {
        is QuotaFetch.WrongCredentials -> result.message
        is QuotaFetch.Unreachable -> result.message
        is QuotaFetch.UntrustedCertificate -> result.message
        is QuotaFetch.ServiceChanged -> result.message
        is QuotaFetch.Success -> ""
    }

    private companion object {
        const val TAG = "Cuota"

        /** No se consulta dos veces seguidas en menos de esto (al abrir y conectar casi a la vez, por ejemplo). */
        val MIN_GAP = 1.minutes

        /** Cuántos puntos por debajo de un umbral tiene que bajar el consumo para que su aviso (o la pausa) vuelva a valer. */
        const val REARM_MARGIN = 5
    }
}

/** «71 %». */
internal fun percentText(quota: UciQuota): String = "${floor(quota.percent).toInt()} %"

/** «1420,5 MB». */
internal fun mbText(mb: Double): String {
    val tenths = kotlin.math.round(mb * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10},${tenths % 10} MB"
}
