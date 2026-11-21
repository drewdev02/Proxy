package org.uci.proxy.core.fakes

import kotlinx.coroutines.CompletableDeferred
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.port.DnsTransport

/**
 * Servidores DNS en memoria. Cada servidor conoce unos nombres; uno que no está en [zones] no contesta (como si no
 * hubiera red hasta él). Un nombre conocido sin direcciones contesta vacío.
 */
class FakeDnsTransport(
    /** Por servidor, las direcciones de cada nombre. */
    private val zones: Map<String, Map<String, List<String>>> = emptyMap(),
) : DnsTransport {
    /** Cada consulta: el servidor y el nombre. */
    val asked = mutableListOf<Pair<String, String>>()

    /** Si no es null, cada respuesta espera a que se complete: así se prueba un DNS lento. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun exchange(server: String, query: ByteArray, timeoutMs: Long): ByteArray? {
        val name = DnsResponder.parseQuestionOf(query)?.name?.lowercase() ?: return null
        asked += server to name
        gate?.await()
        val zone = zones[server] ?: return null
        val address = zone[name]?.firstOrNull()?.let(::parseIpv4)
        return DnsResponder.reply(query, address)
    }
}
