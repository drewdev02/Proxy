package org.uci.proxy.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import org.uci.proxy.core.packet.formatIpv4
import org.uci.proxy.core.port.ConnectionOwner
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Pregunta al sistema qué app abrió una conexión de la VPN. `getConnectionOwnerUid` existe desde Android 10 y solo
 * responde a la app que es la VPN activa, que es justo quien lo pregunta; antes de Android 10 no se sabe.
 */
class AndroidConnectionOwner(private val context: Context) : ConnectionOwner {
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    override fun appsFor(protocol: Int, source: Int, sourcePort: Int, destination: Int, destinationPort: Int): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val uid = try {
            connectivity.getConnectionOwnerUid(protocol, socketAddress(source, sourcePort), socketAddress(destination, destinationPort))
        } catch (_: SecurityException) {
            // Ya no somos la VPN activa.
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        if (uid == Process.INVALID_UID) return emptyList()
        return context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
    }

    private fun socketAddress(address: Int, port: Int) = InetSocketAddress(InetAddress.getByName(formatIpv4(address)), port)
}
