package org.uci.proxy.core.packet

/** Un bloque CIDR, por ejemplo 10.0.0.0/8. */
data class Ipv4Route(val address: Int, val prefixLength: Int) {
    val addressText: String get() = formatIpv4(address)

    private val mask: Int get() = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)

    fun contains(ip: Int): Boolean = ip and mask == address

    /** True si este bloque contiene entero a [other]. */
    fun covers(other: Ipv4Route): Boolean = prefixLength <= other.prefixLength && contains(other.address)

    fun overlaps(other: Ipv4Route): Boolean = covers(other) || other.covers(this)

    override fun toString(): String = "$addressText/$prefixLength"

    companion object {
        fun parse(cidr: String): Ipv4Route {
            val address = requireNotNull(parseIpv4(cidr.substringBefore('/'))) { "CIDR no válido: $cidr" }
            return Ipv4Route(address, cidr.substringAfter('/').toInt())
        }
    }
}

/**
 * Rangos que nunca se meten en el túnel: redes privadas, enlace local, bucle local y los reservados.
 *
 * La red institucional y el propio proxy suelen estar en una red privada, y deben seguir
 * alcanzándose directamente.
 */
val NON_PUBLIC_RANGES: List<Ipv4Route> = listOf(
    "0.0.0.0/8", // "esta red"
    "10.0.0.0/8", // privada
    "100.64.0.0/10", // NAT de operador
    "127.0.0.0/8", // bucle local
    "169.254.0.0/16", // enlace local
    "172.16.0.0/12", // privada
    "192.0.0.0/24", // asignaciones de protocolo IETF
    "192.0.2.0/24", // documentación
    "192.168.0.0/16", // privada
    "198.18.0.0/15", // pruebas de rendimiento
    "198.51.100.0/24", // documentación
    "203.0.113.0/24", // documentación
    "224.0.0.0/3", // multidifusión y reservado
).map(Ipv4Route::parse)

/**
 * Los bloques CIDR que cubren todo IPv4 salvo los [excluded], con el menor número de bloques posible.
 *
 * Android solo permite añadir rutas, no excluirlas, hasta el API 33; por eso se calcula el complemento.
 */
fun routesExcluding(excluded: List<Ipv4Route>): List<Ipv4Route> = routesExcluding(excluded, from = listOf(Ipv4Route(0, 0)))

/** Los bloques de [from] salvo lo que cubren los [excluded], partiendo solo los que se solapan. */
fun routesExcluding(excluded: List<Ipv4Route>, from: List<Ipv4Route>): List<Ipv4Route> {
    val routes = mutableListOf<Ipv4Route>()

    fun cover(block: Ipv4Route) {
        when {
            excluded.any { it.covers(block) } -> Unit
            excluded.none { it.overlaps(block) } -> routes += block
            else -> {
                // El bloque contiene en parte algo excluido: se parte en sus dos mitades.
                val half = block.prefixLength + 1
                cover(Ipv4Route(block.address, half))
                cover(Ipv4Route(block.address or (1 shl (32 - half)), half))
            }
        }
    }

    from.forEach(::cover)
    return routes
}
