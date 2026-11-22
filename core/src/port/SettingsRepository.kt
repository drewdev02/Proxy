package org.uci.proxy.core.port

import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import org.uci.proxy.core.model.QuotaSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setAnswerConnectivityChecks(enabled: Boolean)

    fun setIntranetDirect(enabled: Boolean)

    fun setVpnFallbackToProxy(enabled: Boolean)

    fun setNameByIp(enabled: Boolean)

    fun setTryRawIp(enabled: Boolean)

    fun setNameServiceEnabled(id: String, enabled: Boolean)

    /** Sube ([delta] negativo) o baja ([delta] positivo) un servicio en la lista. No hace nada si ya está en el extremo. */
    fun moveNameService(id: String, delta: Int)

    /** Añade un servicio propio al final de la lista. Quien llama lo ha validado (ver `IpNameServiceValidator`). */
    fun addNameService(service: IpNameService)

    /** Quita un servicio propio. Los de fábrica no se pueden quitar, solo desactivar. */
    fun removeNameService(id: String)

    /** Deja los servicios como vienen de fábrica y activos, y borra los propios. */
    fun resetNameServices()

    /** Cambia los ajustes de la cuota a partir de los actuales. */
    fun updateQuota(change: (QuotaSettings) -> QuotaSettings)
}
