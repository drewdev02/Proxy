package org.uci.proxy.core.port

import org.uci.proxy.core.packet.parseIpv4

/** Una conexión TCP abierta. Las implementaciones lanzan [TcpException] ante cualquier fallo de red. */
interface TcpConnection {
    /** Lee hasta [maxBytes]. Devuelve null cuando el otro extremo cerró. */
    suspend fun read(maxBytes: Int = DEFAULT_READ_SIZE): ByteArray?

    suspend fun write(bytes: ByteArray)

    /**
     * Cierra solo el sentido de envío: el otro extremo ve el fin de los datos, pero aún puede responder.
     * Hace falta para retransmitir fielmente un cierre a medias de la app.
     */
    suspend fun shutdownOutput()

    fun close()

    companion object {
        const val DEFAULT_READ_SIZE = 8192
    }
}

fun interface TcpConnector {
    /** @throws TcpException si no se puede resolver el nombre o establecer la conexión. */
    suspend fun connect(host: String, port: Int): TcpConnection
}

class TcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Bytes aleatorios de calidad criptográfica, del generador del sistema. */
fun interface RandomBytes {
    fun next(size: Int): ByteArray
}

/**
 * El DNS de la red real del equipo: sus servidores y sus dominios de búsqueda (`uci.cu`). Se lee antes de que el túnel
 * capture el DNS, porque después el sistema solo ve el del túnel.
 */
data class LocalDnsConfig(
    /** IPv4 en notación con puntos, por orden de preferencia. */
    val servers: List<String> = emptyList(),
    /** En minúsculas y sin puntos sobrantes. */
    val searchDomains: List<String> = emptyList(),
) {
    companion object {
        val NONE = LocalDnsConfig()

        /**
         * Lo leído del sistema, limpio: solo servidores IPv4, y dominios en minúsculas, sin puntos sobrantes, sin
         * repetir y solo con caracteres de nombre de dominio (`local`, el de mDNS, no cuenta).
         */
        fun of(servers: List<String>, searchDomains: List<String>): LocalDnsConfig = LocalDnsConfig(
            servers = servers.map { it.trim() }.filter { parseIpv4(it) != null }.distinct(),
            searchDomains = searchDomains
                .map { it.trim().trim('.').lowercase() }
                .filter { it.isNotEmpty() && it != "local" && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in ".-" } }
                .distinct(),
        )
    }
}

/** Lee el DNS de la red real. Cada plataforma lo hace a su manera; si no puede, devuelve [LocalDnsConfig.NONE]. */
fun interface LocalDnsSource {
    suspend fun read(): LocalDnsConfig

    companion object {
        val None = LocalDnsSource { LocalDnsConfig.NONE }
    }
}

/** Manda una consulta DNS por UDP a un servidor y espera su respuesta. */
fun interface DnsTransport {
    /** @return la respuesta, o null si no llegó a tiempo o falló el envío. */
    suspend fun exchange(server: String, query: ByteArray, timeoutMs: Long): ByteArray?
}
