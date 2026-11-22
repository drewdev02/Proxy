package org.uci.proxy.core.port

import org.uci.proxy.core.model.QuotaAlert

/** Avisa a la persona fuera de la app (una notificación del sistema), donde la plataforma lo permite. */
interface QuotaNotifier {
    suspend fun notify(alert: QuotaAlert)

    companion object {
        /** Donde no hay notificaciones propias: el aviso queda en el registro y en la app. */
        val None = object : QuotaNotifier {
            override suspend fun notify(alert: QuotaAlert) = Unit
        }
    }
}
