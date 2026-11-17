package org.uci.proxy.core.platform.macos

import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.platform.CommandRunner

class MacNetworkException(message: String) : Exception(message)

/**
 * Lee y modifica la configuración de red de macOS con las herramientas del sistema.
 * Modificarla exige ejecutar como root.
 */
class MacNetwork(private val runner: CommandRunner) {
    /** Puerta de enlace por la que hoy sale el tráfico, o null si el equipo no tiene red. */
    fun defaultGateway(): String? =
        runner.run(listOf(ROUTE, "-n", "get", "default"), null).output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("gateway:") }
            ?.substringAfter(':')?.trim()
            ?.takeIf { parseIpv4(it) != null }

    fun configureInterface(name: String, address: String, mtu: Int) = must(
        // En una interfaz punto a punto se indican los dos extremos; aquí ambos son la misma dirección.
        listOf(IFCONFIG, name, "inet", address, address, "netmask", "255.255.255.255", "mtu", mtu.toString(), "up"),
    )

    fun addRouteThroughInterface(route: Ipv4Route, interfaceName: String) =
        must(listOf(ROUTE, "-n", "add", "-net", route.toString(), "-interface", interfaceName))

    fun addHostRouteThroughGateway(address: String, gateway: String) =
        must(listOf(ROUTE, "-n", "add", "-host", address, gateway))

    /** No falla si la ruta ya no existe: se llama al limpiar. */
    fun removeHostRoute(address: String) {
        runner.run(listOf(ROUTE, "-n", "delete", "-host", address), null)
    }

    /**
     * Publica en la configuración dinámica del sistema un resolvedor propio que atiende [domains].
     *
     * Es una entrada aparte: no modifica el DNS de los servicios de red del equipo ni es persistente
     * (un reinicio la borra). Mientras exista, las consultas de esos dominios van a [server].
     *
     * Límite conocido: si otra VPN ya conectada publica también un resolvedor para todos los dominios, gana la
     * suya. macOS ordena estos resolvedores por el rango del servicio de red e ignora `SearchOrder` en esta clave.
     *
     * @param domains dominios que atiende; la cadena vacía significa "todos".
     */
    fun publishDnsResolver(server: String, domains: List<String>) {
        require(parseIpv4(server) != null) { "Servidor DNS no válido: $server" }
        require(domains.isNotEmpty() && domains.all(::isSafeDomain)) { "Dominios no válidos: $domains" }
        val script = buildString {
            appendLine("d.init")
            appendLine("d.add ServerAddresses * $server")
            appendLine("d.add SupplementalMatchDomains * " + domains.joinToString(" ") { if (it.isEmpty()) "\"\"" else it })
            // Sin esto macOS añade esos dominios a los de búsqueda, y prueba "nombre.dominio" para cada nombre corto.
            appendLine("d.add SupplementalMatchDomainsNoSearch # 1")
            appendLine("set $DNS_RESOLVER_KEY")
            appendLine("quit")
        }
        must(listOf(SCUTIL), script)
    }

    /** Retira el resolvedor de [publishDnsResolver]. No falla si no existe: se llama al limpiar y al arrancar. */
    fun removeDnsResolver() {
        runner.run(listOf(SCUTIL), "remove $DNS_RESOLVER_KEY\nquit\n")
    }

    // Los dominios se intercalan en un guion de scutil: solo letras, dígitos, puntos y guiones.
    private fun isSafeDomain(domain: String): Boolean = domain.all { it.isLetterOrDigit() && it.code < 128 || it in ".-" }

    private fun must(command: List<String>, input: String? = null) {
        val result = runner.run(command, input)
        if (result.exitCode != 0) {
            throw MacNetworkException("${command.joinToString(" ")} falló (${result.exitCode}): ${result.output.trim()}")
        }
    }

    companion object {
        const val DNS_RESOLVER_KEY = "State:/Network/Service/ProxyUCI/DNS"
        private const val ROUTE = "/sbin/route"
        private const val IFCONFIG = "/sbin/ifconfig"
        private const val SCUTIL = "/usr/sbin/scutil"
    }
}
