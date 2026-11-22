package org.uci.proxy.core.port

/** Avisa a la persona fuera de la app (una notificación del sistema), donde la plataforma lo permite. */
fun interface QuotaNotifier {
    fun notify(title: String, message: String)

    companion object {
        /** Donde no hay notificaciones propias: el aviso queda en el registro y en la app. */
        val None = QuotaNotifier { _, _ -> }
    }
}
