package org.uci.proxy.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource

/**
 * El DNS de la red real (Wi-Fi, datos), sin contar ninguna VPN: la activa si no es una VPN; si lo es (al reconectar),
 * la primera red con Internet que no lo sea.
 */
class AndroidLocalDnsSource(private val context: Context) : LocalDnsSource {
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    @Suppress("DEPRECATION") // allNetworks: no hay otra forma de recorrerlas sin registrar un callback.
    override suspend fun read(): LocalDnsConfig {
        val manager = connectivity ?: return LocalDnsConfig.NONE
        val network = manager.activeNetwork?.takeIf { isUnderlying(manager, it) }
            ?: manager.allNetworks.firstOrNull { isUnderlying(manager, it) }
            ?: return LocalDnsConfig.NONE
        val properties = manager.getLinkProperties(network) ?: return LocalDnsConfig.NONE
        val servers = properties.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        // Los dominios de búsqueda llegan en una sola cadena, separados por espacios o comas.
        val domains = properties.domains.orEmpty().split(' ', ',')
        return LocalDnsConfig.of(servers, domains)
    }

    private fun isUnderlying(manager: ConnectivityManager, network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
