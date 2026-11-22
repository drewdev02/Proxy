package org.uci.proxy.core.port

/** El resultado de una consulta. */
sealed interface QuotaFetch {
    data class Success(val assignedMb: Long, val usedMb: Double, val level: String) : QuotaFetch

    /** El servicio rechazó el usuario o la contraseña. */
    data class WrongCredentials(val message: String) : QuotaFetch

    /** No se llegó al servicio: fuera de la red de la UCI, sin red, o no contestó a tiempo. */
    data class Unreachable(val message: String) : QuotaFetch

    /** El certificado del servicio no se pudo verificar: quizá es de una autoridad interna de la UCI. */
    data class UntrustedCertificate(val message: String) : QuotaFetch

    /** Contestó algo que no se entiende: el servicio cambió. */
    data class ServiceChanged(val message: String) : QuotaFetch
}

/** Pregunta la cuota de una cuenta de la UCI. */
fun interface UciQuotaService {
    suspend fun fetch(account: String, password: String, url: String, verifyCertificate: Boolean): QuotaFetch
}
