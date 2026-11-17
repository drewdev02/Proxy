package org.uci.proxy.core.dns

/**
 * Asigna a cada nombre de host una dirección inventada dentro de la subred virtual del túnel
 * y recuerda la correspondencia en ambos sentidos.
 *
 * Así las apps "resuelven" sin que exista un DNS real, y cuando conectan a la dirección inventada
 * el túnel sabe qué nombre pedirle al proxy, que es quien resuelve de verdad.
 *
 * No es segura entre hilos: la usa solo el bucle del motor.
 *
 * @param subnetBase dirección de red de una subred /16, por ejemplo 10.57.0.0.
 */
class FakeDnsTable(private val subnetBase: Int) {
    private val addressByHost = HashMap<String, Int>()
    private val hostByAddress = HashMap<Int, String>()
    private var nextOffset = FIRST_OFFSET

    init {
        require(subnetBase and HOST_MASK == 0) { "La subred virtual debe ser un /16" }
    }

    fun contains(address: Int): Boolean = address and HOST_MASK.inv() == subnetBase

    /** Dirección ya asignada a [host] o una nueva. Los nombres no distinguen mayúsculas. */
    fun addressFor(host: String): Int {
        val key = host.lowercase()
        addressByHost[key]?.let { return it }

        val address = subnetBase or nextOffset
        nextOffset = if (nextOffset == LAST_OFFSET) FIRST_OFFSET else nextOffset + 1

        // Agotado el rango se reutiliza la dirección más antigua, que deja de valer para su nombre anterior.
        hostByAddress.remove(address)?.let(addressByHost::remove)
        addressByHost[key] = address
        hostByAddress[address] = key
        return address
    }

    fun hostFor(address: Int): String? = hostByAddress[address]

    companion object {
        private const val HOST_MASK = 0xFFFF

        // El primer /24 (x.x.0.*) queda para la interfaz y su servidor DNS.
        const val FIRST_OFFSET = 0x0100
        const val LAST_OFFSET = 0xFFFE
    }
}
