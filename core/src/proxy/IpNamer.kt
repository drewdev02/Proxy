package org.uci.proxy.core.proxy

import org.uci.proxy.core.packet.NON_PUBLIC_RANGES
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService

/** Una forma de pedirle al proxy un destino: con qué nombre, y de qué servicio salió (null si es el destino tal cual). */
class NameAttempt(val host: String, val service: IpNameService?)

/**
 * Decide con qué nombres se le pide al proxy una IP pública, y en qué orden.
 *
 * El Squid de la UCI rechaza (403) los `CONNECT` a una IP pública escrita tal cual, pero acepta el mismo destino por
 * nombre. Se prueban los servicios activos en el orden de Ajustes, empezando por el que funcionó la última vez, y al
 * final la IP tal cual si así se pidió. Es un objeto de corta vida: recuerda el último que sirvió solo durante la
 * conexión en curso.
 */
class IpNamer(
    private val services: List<IpNameService>,
    private val nameByIp: Boolean,
    private val tryRawIp: Boolean,
) {
    constructor(settings: AppSettings) : this(settings.nameServices, settings.nameByIp, settings.tryRawIp)

    private var lastGoodId: String? = null

    /**
     * Los intentos para llegar a [host], en orden. Un nombre o una IP privada se piden tal cual (un solo intento): solo las
     * IP públicas escritas como número tienen problema con el proxy.
     */
    fun attempts(host: String): List<NameAttempt> {
        val raw = NameAttempt(host, service = null)
        if (!nameByIp || !isPublicRawIpv4(host)) return listOf(raw)

        val named = services.filter { it.enabled }
            .sortedBy { if (it.id == lastGoodId) 0 else 1 }
            .map { NameAttempt(it.nameFor(host), it) }
        // Sin ningún servicio activo la IP tal cual es lo único que queda, aunque se hubiera pedido no usarla.
        return if (named.isEmpty() || tryRawIp) named + raw else named
    }

    /** El proxy aceptó este intento: la próxima vez se prueba primero. */
    fun succeeded(attempt: NameAttempt) {
        attempt.service?.let { lastGoodId = it.id }
    }

    private companion object {
        fun isPublicRawIpv4(host: String): Boolean {
            val address = parseIpv4(host) ?: return false
            return NON_PUBLIC_RANGES.none { it.contains(address) }
        }
    }
}
