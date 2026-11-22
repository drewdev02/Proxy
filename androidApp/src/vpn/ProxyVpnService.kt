package org.uci.proxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.koin.android.ext.android.inject
import org.uci.proxy.MainActivity
import org.uci.proxy.R
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.platform.AndroidPlatformTunnel
import org.uci.proxy.core.platform.TunnelServiceContract
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.settings.SettingsRepository

/**
 * Servicio de VPN: punto de entrada que crea el sistema. Solo traduce las órdenes de Android
 * (arrancar, parar, revocar) a [TunnelRunner], que es quien contiene la lógica del túnel.
 */
class ProxyVpnService : VpnService() {
    // Punto de entrada que crea el sistema: aquí sí se obtienen las dependencias de Koin directamente.
    private val runner: TunnelRunner by inject()
    private val dispatchers: AppDispatchers by inject()
    private val settings: SettingsRepository by inject()
    private val monitor: TunnelMonitor by inject()

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatchers.background) }
    // Lo tocan el hilo principal (órdenes) y el de fondo (fin del túnel).
    @Volatile
    private var tunnelJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TunnelServiceContract.ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(TunnelServiceContract.EXTRA_PROFILE_ID)
                // Android exige mostrar la notificación enseguida tras startForegroundService().
                showForegroundNotification()
                if (profileId == null) stopTunnel() else startTunnel(profileId)
            }
            TunnelServiceContract.ACTION_DISCONNECT -> stopTunnel()
        }
        // Si el sistema mata el proceso no se relanza solo: reconectar sin el perfil no tendría sentido.
        return START_NOT_STICKY
    }

    private fun startTunnel(profileId: String) {
        tunnelJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Avisa cuando Otro País cae o vuelve (con reglas de salida, su tráfico pasa al proxy mientras tanto).
            val alerts = launch { watchVpnDown() }
            try {
                runner.run(AndroidPlatformTunnel(this@ProxyVpnService, dispatchers.background), profileId, settings.settings.value)
            } finally {
                alerts.cancel()
                getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
                // El túnel terminó, por orden o porque el sistema cerró la interfaz. Si ya hay otro
                // túnel en marcha, la notificación y el servicio son suyos y no se tocan.
                val replaced = tunnelJob.let { it != null && it !== coroutineContext[Job] }
                if (!replaced) {
                    tunnelJob = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // Sin identificador de arranque: la orden de desconectar es más nueva que la de
                    // conectar, y stopSelf(startId) con el identificador antiguo no detendría nada.
                    stopSelf()
                }
            }
        }
        tunnelJob = job
        job.start()
    }

    private fun stopTunnel() {
        val running = tunnelJob
        tunnelJob = null
        if (running == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            running.cancel()
        }
    }

    /** La persona desconectó la VPN desde los ajustes del sistema, u otra app VPN tomó el relevo. */
    override fun onRevoke() {
        stopTunnel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Un aviso cada vez que Otro País cambia de estado; la primera vez solo si arrancó caído. */
    private suspend fun watchVpnDown() {
        var wasDown = false
        monitor.state
            .map { (it as? TunnelState.Connected)?.vpnDown }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { down ->
                if (down == wasDown) return@collect
                wasDown = down
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.vpn_alerts_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
                )
                val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vpn_notification)
                    .setContentTitle(getString(if (down) R.string.vpn_down_title else R.string.vpn_back_title))
                    .setContentText(getString(if (down) R.string.vpn_down_text else R.string.vpn_back_text))
                    .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build()
                manager.notify(ALERT_NOTIFICATION_ID, notification)
            }
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 exige declarar el tipo; "systemExempted" es el previsto para apps de VPN.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "vpn"
        const val NOTIFICATION_ID = 1
        const val ALERT_CHANNEL_ID = "vpn-alerts"
        const val ALERT_NOTIFICATION_ID = 2
    }
}
