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

/** Por qué falló una conexión TCP, para poder decir algo más útil que «no se pudo conectar». */
enum class NetFailure {
    /** El equipo no tiene red: ninguna interfaz con salida. */
    NO_NETWORK,

    /** El nombre no existe en el DNS, o el DNS no contesta. */
    NAME_NOT_FOUND,

    /** El equipo existe, pero nadie escucha en ese puerto. */
    REFUSED,

    /** No contestó a tiempo. */
    TIMEOUT,

    /** No hay ruta hasta el equipo: está apagado o una red intermedia lo impide. */
    NO_ROUTE,

    /** El otro lado cortó la conexión de golpe. */
    RESET,

    /** El otro lado cerró la conexión antes de terminar de responder. */
    CLOSED,

    /** Respondió algo que no es lo esperado (por ejemplo, no es un proxy HTTP). */
    PROTOCOL,

    OTHER,
}

/** Un fallo de red. El mensaje es para el registro; [kind] es lo que la interfaz cuenta. */
class TcpException(val kind: NetFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Clasifica una excepción de la librería de red. Cada plataforma lanza tipos distintos (las de `java.net` en JVM y
 * Android, errores POSIX en iOS), así que se mira el nombre de la clase y el mensaje de cada causa.
 */
fun classifyNetworkError(error: Throwable): NetFailure {
    for (cause in generateSequence(error) { it.cause }.take(MAX_CAUSES)) {
        if (cause is TcpException) return cause.kind
        val name = cause::class.simpleName.orEmpty()
        val message = cause.message.orEmpty().lowercase()
        fun says(vararg words: String) = words.any { it in message }
        when {
            name == "UnknownHostException" || name == "UnresolvedAddressException" ||
                says("nodename nor servname", "no address associated", "unable to resolve", "name or service not known", "unknown host") ->
                return NetFailure.NAME_NOT_FOUND
            says("network is unreachable", "enetunreach", "network is down", "enetdown") -> return NetFailure.NO_NETWORK
            name == "NoRouteToHostException" || says("no route to host", "host is unreachable", "ehostunreach") -> return NetFailure.NO_ROUTE
            says("connection refused", "econnrefused") -> return NetFailure.REFUSED
            name.contains("Timeout") || says("timed out", "etimedout", "timeout") -> return NetFailure.TIMEOUT
            says("connection reset", "econnreset", "broken pipe", "epipe") -> return NetFailure.RESET
            name == "ConnectException" -> return NetFailure.REFUSED
        }
    }
    return NetFailure.OTHER
}

private const val MAX_CAUSES = 8

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
