package org.uci.proxy.core.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelState

/** Órdenes que entiende el servicio de VPN. */
object TunnelServiceContract {
    const val ACTION_CONNECT = "org.uci.proxy.action.CONNECT"
    const val ACTION_DISCONNECT = "org.uci.proxy.action.DISCONNECT"
    const val EXTRA_PROFILE_ID = "profileId"
}

/**
 * Arranca y detiene el túnel pidiendo antes los permisos que hacen falta. El túnel en sí corre
 * en el servicio de VPN, que el sistema mantiene vivo aunque la app pase a segundo plano.
 *
 * @param serviceClass el servicio de VPN declarado en el manifest de la app.
 */
class AndroidTunnelController(
    private val context: Context,
    private val serviceClass: Class<out Service>,
    private val localNetworkPermission: LocalNetworkPermission,
    private val vpnConsent: AndroidVpnConsent,
    private val stateStore: TunnelStateStore,
) : TunnelControl {
    override val isAvailable = true

    override suspend fun connect(profileId: String) {
        if (!localNetworkPermission.request()) {
            stateStore.set(TunnelState.Failed(TunnelFailure.LOCAL_NETWORK_DENIED))
            return
        }
        if (!vpnConsent.request()) {
            stateStore.set(TunnelState.Failed(TunnelFailure.VPN_PERMISSION_DENIED))
            return
        }
        stateStore.set(TunnelState.Connecting(profileId))
        context.startForegroundService(
            Intent(context, serviceClass)
                .setAction(TunnelServiceContract.ACTION_CONNECT)
                .putExtra(TunnelServiceContract.EXTRA_PROFILE_ID, profileId),
        )
    }

    override suspend fun disconnect() {
        // startService y no stopService: el servicio cierra el túnel con orden antes de pararse.
        context.startService(Intent(context, serviceClass).setAction(TunnelServiceContract.ACTION_DISCONNECT))
    }
}
