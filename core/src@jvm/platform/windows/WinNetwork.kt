package org.uci.proxy.core.platform.windows

import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.platform.CommandRunner

class WindowsNetworkException(message: String) : Exception(message)

/**
 * Lee y modifica la configuración de red de Windows con `netsh` y `route`.
 * Modificarla exige ejecutar como Administrador.
 */
class WinNetwork(private val runner: CommandRunner) {
    /** Puerta de enlace por la que hoy sale el tráfico (la ruta por defecto de menor métrica), o null sin red. */
    fun defaultGateway(): String? =
        runner.run(listOf(ROUTE, "print", "-4", "0.0.0.0"), null).output.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            // Destino, máscara, puerta de enlace, interfaz y métrica; las rutas "En vínculo" no tienen puerta de enlace.
            .filter { it.size >= 5 && it[0] == "0.0.0.0" && it[1] == "0.0.0.0" && parseIpv4(it[2]) != null }
            .minByOrNull { it.last().toIntOrNull() ?: Int.MAX_VALUE }
            ?.get(2)

    /**
     * Da al adaptador su dirección en la subred /16 del túnel y su DNS, y le pone la métrica más baja para que
     * Windows prefiera su DNS al de las demás interfaces.
     */
    fun configureAdapter(adapter: String, address: String, dnsServer: String, mtu: Int) {
        requireSafeName(adapter)
        require(parseIpv4(address) != null) { "Dirección no válida: $address" }
        require(parseIpv4(dnsServer) != null) { "Servidor DNS no válido: $dnsServer" }
        must(listOf(NETSH, "interface", "ip", "set", "address", "name=$adapter", "source=static", "addr=$address", "mask=$TUNNEL_MASK", "gateway=none"))
        must(listOf(NETSH, "interface", "ip", "set", "dns", "name=$adapter", "source=static", "addr=$dnsServer", "validate=no"))
        must(listOf(NETSH, "interface", "ipv4", "set", "subinterface", adapter, "mtu=$mtu", "store=active"))
        must(listOf(NETSH, "interface", "ipv4", "set", "interface", adapter, "metric=1"))
    }

    /** Ruta "en vínculo" por el adaptador: no necesita puerta de enlace, y vale solo mientras exista el adaptador. */
    fun addRouteThroughAdapter(route: Ipv4Route, adapter: String) {
        requireSafeName(adapter)
        must(listOf(NETSH, "interface", "ipv4", "add", "route", "prefix=$route", "interface=$adapter", "nexthop=0.0.0.0", "metric=1", "store=active"))
    }

    fun addHostRouteThroughGateway(address: String, gateway: String) {
        require(parseIpv4(address) != null && parseIpv4(gateway) != null) { "Dirección no válida: $address por $gateway" }
        must(listOf(ROUTE, "add", address, "mask", "255.255.255.255", gateway, "metric", "1"))
    }

    /** No falla si la ruta ya no existe: se llama al limpiar. */
    fun removeHostRoute(address: String) {
        if (parseIpv4(address) == null) return
        runner.run(listOf(ROUTE, "delete", address), null)
    }

    /** Descarta las respuestas DNS guardadas, que pueden ser de antes de conectar. Es un favor: no falla. */
    fun flushDns() {
        runner.run(listOf(IPCONFIG, "/flushdns"), null)
    }

    // El nombre viaja como argumento de netsh: solo letras, dígitos, guion y guion bajo.
    private fun requireSafeName(adapter: String) =
        require(adapter.isNotEmpty() && adapter.all { it.isLetterOrDigit() && it.code < 128 || it in "-_" }) { "Nombre de adaptador no válido: $adapter" }

    private fun must(command: List<String>) {
        val result = runner.run(command, null)
        if (result.exitCode != 0) {
            throw WindowsNetworkException("${command.joinToString(" ")} falló (${result.exitCode}): ${result.output.trim()}")
        }
    }

    private companion object {
        const val NETSH = "netsh"
        const val ROUTE = "route"
        const val IPCONFIG = "ipconfig"

        // El plan de direcciones del túnel es siempre una subred /16 (10.A.0.0/16).
        const val TUNNEL_MASK = "255.255.0.0"
    }
}
