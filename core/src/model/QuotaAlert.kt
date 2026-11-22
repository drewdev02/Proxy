package org.uci.proxy.core.model

/** De qué avisa la cuota fuera de la app. Los textos, en el idioma del sistema, salen de `title()` y `message()`, en shared. */
sealed interface QuotaAlert {
    val quota: UciQuota

    /** Se pasó el umbral de aviso [threshold] (el más alto de los recién pasados). */
    data class Threshold(val threshold: Int, override val quota: UciQuota) : QuotaAlert

    /** Se llegó al límite y se desconectó el túnel. */
    data class Paused(override val quota: UciQuota) : QuotaAlert
}
