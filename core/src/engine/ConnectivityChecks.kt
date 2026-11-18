package org.uci.proxy.core.engine

import org.uci.proxy.core.packet.parseIpv4
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Si el proxy ha abierto alguna conexión hace poco. Sin eso no se puede asegurar que haya internet. */
class ProxyHealth(
    private val timeSource: TimeSource,
    private val window: Duration = 5.minutes,
) {
    // Lo escriben los relés de cada conexión, que pueden correr en hilos distintos.
    @Volatile
    private var lastSuccess: TimeMark? = null

    fun markSuccess() {
        lastSuccess = timeSource.markNow()
    }

    fun isHealthy(): Boolean = lastSuccess?.let { it.elapsedNow() <= window } ?: false
}

/**
 * Contesta en local las comprobaciones de conectividad que Android, Windows y macOS hacen para decidir si hay internet.
 * El proxy institucional suele bloquearlas (403, 503) y el sistema muestra «sin internet» aunque el tráfico pase.
 *
 * Solo contesta si está activado y el proxy funciona: con el proxy caído o con la contraseña mal, el aviso de
 * «sin internet» es cierto y no se debe tapar.
 */
class ConnectivityChecks(
    private val enabled: Boolean,
    private val health: ProxyHealth,
) {
    /** True si una conexión a [host]:[port] podría ser una de estas comprobaciones: merece la pena leer su petición. */
    fun mightBeCheck(host: String, port: Int): Boolean =
        enabled && port == HTTP_PORT && probesByHost.containsKey(host.lowercase())

    /**
     * @param request los primeros bytes que envió la app (la petición HTTP).
     * @return la respuesta HTTP completa, o null si no es una comprobación conocida o no toca contestarla.
     */
    fun replyFor(host: String, port: Int, request: ByteArray): ByteArray? {
        if (!enabled || port != HTTP_PORT || !health.isHealthy()) return null
        val probe = probesByHost[host.lowercase()] ?: return null
        val path = requestPath(request) ?: return null
        return if (path in probe.paths) probe.response else null
    }

    /** La dirección que el sistema espera al resolver [name], o null si ese nombre no tiene una especial. */
    fun dnsAddressFor(name: String): Int? {
        if (!enabled || !health.isHealthy()) return null
        return if (name.equals(WINDOWS_DNS_PROBE_HOST, ignoreCase = true)) WINDOWS_DNS_PROBE_ADDRESS else null
    }

    private class Probe(val paths: Set<String>, val response: ByteArray)

    private companion object {
        const val HTTP_PORT = 80
        const val WINDOWS_DNS_PROBE_HOST = "dns.msftncsi.com"
        val WINDOWS_DNS_PROBE_ADDRESS = parseIpv4("131.107.255.255")!!

        // Android espera un 204 sin cuerpo.
        val noContent = httpResponse("204 No Content", body = "")
        val androidPaths = setOf("/generate_204", "/gen_204")

        // Windows espera 200 y un texto exacto.
        val connectTest = httpResponse("200 OK", body = "Microsoft Connect Test", contentType = "text/plain")
        val ncsi = httpResponse("200 OK", body = "Microsoft NCSI", contentType = "text/plain")

        // macOS espera 200 y esta página; si no la recibe, abre el asistente de portal cautivo.
        val appleSuccess = httpResponse(
            "200 OK",
            body = "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>",
            contentType = "text/html",
        )

        val probesByHost: Map<String, Probe> = buildMap {
            listOf(
                "connectivitycheck.gstatic.com",
                "connectivitycheck.android.com",
                "clients3.google.com",
                "www.google.com",
                "play.googleapis.com",
            ).forEach { put(it, Probe(androidPaths, noContent)) }
            listOf("www.msftconnecttest.com", "ipv6.msftconnecttest.com")
                .forEach { put(it, Probe(setOf("/connecttest.txt"), connectTest)) }
            listOf("www.msftncsi.com", "ipv6.msftncsi.com")
                .forEach { put(it, Probe(setOf("/ncsi.txt"), ncsi)) }
            put("captive.apple.com", Probe(setOf("/hotspot-detect.html"), appleSuccess))
            put("www.apple.com", Probe(setOf("/library/test/success.html"), appleSuccess))
        }

        fun httpResponse(status: String, body: String, contentType: String? = null): ByteArray {
            val type = contentType?.let { "Content-Type: $it\r\n" }.orEmpty()
            val bytes = body.encodeToByteArray()
            return ("HTTP/1.1 $status\r\n${type}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").encodeToByteArray() + bytes
        }

        /** La ruta de una petición `GET /ruta HTTP/1.1`, sin parámetros; null si no se entiende. */
        fun requestPath(request: ByteArray): String? {
            val line = request.decodeToString(0, minOf(request.size, MAX_REQUEST_LINE)).substringBefore("\r\n").substringBefore('\n')
            val parts = line.split(' ')
            if (parts.size < 2 || parts[0] != "GET") return null
            return parts[1].substringBefore('?')
        }

        const val MAX_REQUEST_LINE = 512
    }
}
