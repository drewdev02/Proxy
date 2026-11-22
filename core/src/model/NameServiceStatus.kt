package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

/** Qué pasó la última vez que se pidió un destino al proxy con el nombre de un servicio. */
enum class NameOutcome {
    /** El proxy abrió el túnel. */
    WORKED,

    /** El proxy rechazó el nombre (403 o similar). */
    REFUSED,
}

@Serializable
data class NameServiceStatus(val outcome: NameOutcome, val atMillis: Long)
