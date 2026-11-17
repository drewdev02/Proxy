package org.uci.proxy.core.platform.macos

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.platform.PollingTunDevice
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.port.TunnelUnavailableException

/**
 * Túnel en macOS sobre una interfaz utun. Debe ejecutarse como root.
 *
 * El DNS se captura publicando un resolvedor propio en la configuración dinámica del sistema, que
 * apunta al servidor DNS del túnel. Desviar por rutas los servidores DNS del equipo no basta: macOS
 * envía las consultas por la interfaz a la que está ligado cada resolvedor, sin mirar las rutas.
 *
 * La interfaz y sus rutas desaparecen con el proceso. El resolvedor y la ruta directa al proxy
 * hay que retirarlos: se hace al cerrar, y también al arrancar por si una ejecución anterior murió
 * sin limpiar.
 *
 * @param dnsDomains dominios cuyo DNS se captura. La cadena vacía significa "todos"; una lista vacía,
 *   ninguno. Limitarlo a dominios concretos solo sirve para pruebas acotadas.
 */
class MacPlatformTunnel(
    private val network: MacNetwork,
    private val utunOpener: UtunOpener,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
    private val dnsDomains: List<String> = ALL_DOMAINS,
) : PlatformTunnel {
    override suspend fun establish(config: TunConfig): TunDevice = withContext(ioDispatcher) {
        network.removeDnsResolver()

        // Se lee antes de tocar nada: es la puerta de enlace real del equipo.
        val gateway = network.defaultGateway()

        val utun = try {
            utunOpener.open()
        } catch (e: MacNetworkException) {
            throw TunnelUnavailableException(e.message.orEmpty())
        }

        var proxyBypass: String? = null
        try {
            network.configureInterface(utun.name, config.address, config.mtu)

            // Si el proxy cae dentro de lo que se enruta al túnel, necesita una ruta propia por la red real.
            val proxy = parseIpv4(config.proxyAddress)
            if (proxy != null && config.routes.any { it.contains(proxy) }) {
                if (gateway == null) throw MacNetworkException("No hay puerta de enlace para alcanzar el proxy")
                network.addHostRouteThroughGateway(config.proxyAddress, gateway)
                proxyBypass = config.proxyAddress
            }

            config.routes.forEach { network.addRouteThroughInterface(it, utun.name) }

            // El DNS se captura solo con el resolvedor propio. No se desvían al túnel los servidores DNS de la
            // red: en muchas redes ese servidor es la propia puerta de enlace, y una ruta hacia ella por el
            // túnel rompe a ratos las conexiones que salen por la red real (las del proxy, si es local).
            if (dnsDomains.isNotEmpty()) network.publishDnsResolver(config.dnsServer, dnsDomains)

            logger.log(LogLevel.INFO, TAG, "Interfaz ${utun.name} lista; DNS capturado para: ${describe(dnsDomains)}")
            // Al cerrar el descriptor el núcleo destruye la interfaz y, con ella, todas sus rutas.
            PollingTunDevice(utun, ioDispatcher) { cleanUp(proxyBypass) }
        } catch (e: MacNetworkException) {
            utun.close()
            cleanUp(proxyBypass)
            throw TunnelUnavailableException(e.message.orEmpty())
        }
    }

    private fun cleanUp(proxyBypass: String?) {
        network.removeDnsResolver()
        proxyBypass?.let(network::removeHostRoute)
    }

    private fun describe(domains: List<String>): String = when {
        domains.isEmpty() -> "nada"
        domains == ALL_DOMAINS -> "todos los dominios"
        else -> domains.joinToString()
    }

    companion object {
        private const val TAG = "MacTunnel"

        /** La cadena vacía es, para macOS, el dominio que casa con cualquier nombre. */
        val ALL_DOMAINS = listOf("")
    }
}
