package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

/** La cuota de navegación de una cuenta de la UCI, tal como la dio el servicio. */
@Serializable
data class UciQuota(
    val assignedMb: Long,
    val usedMb: Double,
    /** El nivel de navegación de la cuenta (estudiante, profesor…), tal cual lo da la UCI. */
    val level: String,
    /** Cuándo se consultó, en milisegundos desde 1970. */
    val checkedAtMillis: Long,
) {
    /** Cuánto se ha gastado, de 0 a 100 (o más, si el servicio da más de lo asignado). */
    val percent: Double get() = if (assignedMb <= 0) 0.0 else usedMb * 100 / assignedMb

    val remainingMb: Double get() = (assignedMb - usedMb).coerceAtLeast(0.0)
}
