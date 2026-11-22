package org.uci.proxy.quota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.uci.proxy.MainActivity
import org.uci.proxy.R

/** Los avisos de la cuota como notificaciones de Android, en su propio canal para que se puedan silenciar aparte. */
class AndroidQuotaNotifier(private val context: Context) : QuotaNotifier {
    override suspend fun notify(alert: QuotaAlert) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Los textos, en el idioma del sistema, de los recursos de Compose del módulo compartido.
        val title = alert.title()
        val message = alert.message()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.quota_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        // Sin el permiso de notificaciones (Android 13+) no se muestra; el aviso sigue en la app y en el registro.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "quota"
        const val NOTIFICATION_ID = 7
    }
}
