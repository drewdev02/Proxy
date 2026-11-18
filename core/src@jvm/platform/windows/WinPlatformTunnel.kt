package org.uci.proxy.core.platform.windows

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.platform.PacketChannel
import org.uci.proxy.core.platform.PollingTunDevice
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.port.TunnelUnavailableException

fun interface WintunOpener {
    /**
     * Crea el adaptador [adapterName] y abre una sesión sobre él. Al cerrar el canal el adaptador desaparece.
     * @throws WindowsNetworkException si no se puede, normalmente por no ser Administrador.
     */
    fun open(adapterName: String): PacketChannel
}

/**
 * Túnel en Windows sobre un adaptador Wintun. Debe ejecutarse como Administrador.
 *
 * El DNS se captura fijando como DNS del adaptador el servidor del túnel y dándole la métrica más baja,
 * de modo que Windows lo consulte antes que a los de las demás interfaces.
 *
 * Wintun retira el adaptador, con sus rutas, al cerrar la sesión. Solo la ruta directa al proxy
 * hay que quitarla a mano.
 */
class WinPlatformTunnel(
    private val network: WinNetwork,
    private val wintunOpener: WintunOpener,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : PlatformTunnel {
    override suspend fun establish(config: TunConfig): TunDevice = withContext(ioDispatcher) {
        // Se lee antes de tocar nada: es la puerta de enlace real del equipo.
        val gateway = network.defaultGateway()

        val channel = try {
            wintunOpener.open(ADAPTER_NAME)
        } catch (e: WindowsNetworkException) {
            throw TunnelUnavailableException(e.message.orEmpty())
        }

        var proxyBypass: String? = null
        try {
            network.configureAdapter(ADAPTER_NAME, config.address, config.dnsServer, config.mtu, config.netmask)

            // Si el proxy cae dentro de lo que se enruta al túnel, necesita una ruta propia por la red real.
            val proxy = parseIpv4(config.proxyAddress)
            if (proxy != null && config.routes.any { it.contains(proxy) }) {
                if (gateway == null) throw WindowsNetworkException("No hay puerta de enlace para alcanzar el proxy")
                network.addHostRouteThroughGateway(config.proxyAddress, gateway)
                proxyBypass = config.proxyAddress
            }

            config.routes.forEach { network.addRouteThroughAdapter(it, ADAPTER_NAME) }
            network.flushDns()

            logger.log(LogLevel.INFO, TAG, "Adaptador $ADAPTER_NAME listo; DNS del túnel ${config.dnsServer}")
            PollingTunDevice(channel, ioDispatcher) { cleanUp(proxyBypass) }
        } catch (e: WindowsNetworkException) {
            channel.close()
            cleanUp(proxyBypass)
            throw TunnelUnavailableException(e.message.orEmpty())
        }
    }

    private fun cleanUp(proxyBypass: String?) {
        proxyBypass?.let(network::removeHostRoute)
        network.flushDns()
    }

    companion object {
        private const val TAG = "WinTunnel"

        /** Nombre del adaptador en Windows; las órdenes de `netsh` lo usan para identificarlo. */
        const val ADAPTER_NAME = "ProxyUCI"
    }
}
