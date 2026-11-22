package org.uci.proxy.core.proxy

import org.uci.proxy.core.packet.NON_PUBLIC_RANGES
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import org.uci.proxy.core.model.NameOutcome
import org.uci.proxy.core.port.NameServiceReporter
import kotlin.time.TimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Una forma de pedirle al proxy un destino: con qué nombre, y de qué servicio salió (null si es el destino tal cual). */
class NameAttempt(val host: String, val service: IpNameService?)

/**
 * Decide con qué nombres se le pide al proxy una IP pública, y en qué orden.
 *
 * El Squid de la UCI rechaza (403) los `CONNECT` a una IP pública escrita tal cual, pero acepta el mismo destino por
 * nombre. Se prueban los servicios activos en el orden de Ajustes, empezando por el que funcionó la última vez, y al
 * final la IP tal cual si así se pidió.
 *
 * Un servicio que el proxy rechaza [FAILURES_BEFORE_COOLDOWN] veces seguidas se aparta [cooldown], para no pagar un
 * rechazo en cada conexión; si todos estuvieran apartados se prueban igualmente. Es un objeto de corta vida: recuerda
 * el último que sirvió y los rechazos solo durante la conexión en curso.
 */
class IpNamer(
    private val services: List<IpNameService>,
    private val nameByIp: Boolean,
    private val tryRawIp: Boolean,
    private val reporter: NameServiceReporter = NameServiceReporter.None,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val cooldown: Duration = 5.minutes,
) {
    constructor(
        settings: AppSettings,
        reporter: NameServiceReporter = NameServiceReporter.None,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) : this(settings.nameServices, settings.nameByIp, settings.tryRawIp, reporter, timeSource)

    private var lastGoodId: String? = null
    private val consecutiveRefusals = mutableMapOf<String, Int>()
    private val apartUntil = mutableMapOf<String, TimeMark>()

    /**
     * Los intentos para llegar a [host], en orden. Un nombre o una IP privada se piden tal cual (un solo intento): solo las
     * IP públicas escritas como número tienen problema con el proxy.
     */
    fun attempts(host: String): List<NameAttempt> {
        val raw = NameAttempt(host, service = null)
        if (!nameByIp || !isPublicRawIpv4(host)) return listOf(raw)

        val active = services.filter { it.enabled }
        val available = active.filterNot { isApart(it.id) }.ifEmpty { active }
        val named = available
            .sortedBy { if (it.id == lastGoodId) 0 else 1 }
            .map { NameAttempt(it.nameFor(host), it) }
        // Sin ningún servicio activo la IP tal cual es lo único que queda, aunque se hubiera pedido no usarla.
        return if (named.isEmpty() || tryRawIp) named + raw else named
    }

    /** El proxy aceptó este intento: la próxima vez se prueba primero, y se le da otra oportunidad si estaba apartado. */
    fun succeeded(attempt: NameAttempt) {
        val service = attempt.service ?: return
        lastGoodId = service.id
        consecutiveRefusals.remove(service.id)
        apartUntil.remove(service.id)
        reporter.report(service.id, NameOutcome.WORKED)
    }

    /** El proxy rechazó este intento. Tras varios seguidos, el servicio se aparta unos minutos. */
    fun refused(attempt: NameAttempt) {
        val service = attempt.service ?: return
        val count = (consecutiveRefusals[service.id] ?: 0) + 1
        consecutiveRefusals[service.id] = count
        if (count >= FAILURES_BEFORE_COOLDOWN) apartUntil[service.id] = timeSource.markNow() + cooldown
        reporter.report(service.id, NameOutcome.REFUSED)
    }

    private fun isApart(id: String): Boolean {
        val until = apartUntil[id] ?: return false
        if (until.hasPassedNow()) {
            // Cumplido el aislamiento, vuelve con la cuenta a cero.
            apartUntil.remove(id)
            consecutiveRefusals.remove(id)
            return false
        }
        return true
    }

    companion object {
        const val FAILURES_BEFORE_COOLDOWN = 3

        private fun isPublicRawIpv4(host: String): Boolean {
            val address = parseIpv4(host) ?: return false
            return NON_PUBLIC_RANGES.none { it.contains(address) }
        }
    }
}
