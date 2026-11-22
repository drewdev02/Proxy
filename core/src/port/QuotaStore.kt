package org.uci.proxy.core.port

import org.uci.proxy.core.model.UciQuota

/**
 * Lo que se recuerda de la cuota de cada cuenta entre sesiones: la última consultada (para enseñarla al abrir, sin esperar
 * a la red), los avisos ya dados y si se pidió seguir conectado pasado el límite.
 */
interface QuotaStore {
    fun last(account: String): UciQuota?

    fun saveLast(account: String, quota: UciQuota)

    /** Los umbrales de los que ya se avisó y por encima de los que se sigue: no se repiten hasta que el consumo baje. */
    fun alerted(account: String): Set<Int>

    fun saveAlerted(account: String, thresholds: Set<Int>)

    /** Si se pidió seguir conectado pasado el límite de la pausa («Conectar igual»), mientras el consumo no baje. */
    fun overLimitAllowed(account: String): Boolean

    fun setOverLimitAllowed(account: String, allowed: Boolean)
}
