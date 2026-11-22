package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.engine.LocalDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.LocalDnsPolicy
import org.uci.proxy.core.dns.LocalDnsResolver
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PacketWriter

/**
 * Atiende las consultas DNS de los nombres de la red local (ver [LocalDnsPolicy]): si el DNS de la red les da una
 * dirección interna, la app la recibe y su conexión va directa por la red real, porque esas direcciones no entran en el
 * túnel. Si no, la consulta sigue su camino de siempre (el DNS falso o el de la VPN).
 *
 * Además contesta «no existe» a WPAD, que si no el navegador encontraría el proxy de la red y lo usaría por su cuenta.
 *
 * Preguntar a la red lleva un momento y el bucle del motor no puede esperar mucho: si la respuesta tarda, la consulta se
 * contesta después, y si al final no era interna se devuelve al bucle con [requeue] para que siga por el camino de siempre.
 */
class LocalDnsHandler(
    private val localDns: LocalDns?,
    private val output: PacketWriter,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val requeue: (Ipv4Packet) -> Unit,
) : PacketHandler {
    private var wpadLogged = false

    override suspend fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val datagram = UdpDatagram.parse(packet) ?: return null
        if (datagram.destinationPort != DNS_PORT) return null
        val query = datagram.payload
        val question = DnsResponder.parseQuestionOf(query) ?: return null

        if (LocalDnsPolicy.isWpad(question.name)) {
            if (!wpadLogged) {
                wpadLogged = true
                logger.log(LogLevel.DEBUG, TAG, "WPAD (${question.name}): no existe mientras dure el túnel")
            }
            return listOfNotNull(DnsResponder.nameError(query)?.let { reply(packet, datagram, it) })
        }

        val local = localDns ?: return null
        if (!local.policy.claims(question.name)) return null
        // Solo hay direcciones IPv4: las demás preguntas de estos nombres (AAAA, HTTPS) se contestan vacías.
        if (question.type != DnsResponder.TYPE_A) {
            return listOfNotNull(DnsResponder.reply(query, address = null)?.let { reply(packet, datagram, it) })
        }

        val known = local.resolver.known(question.name)
        if (known != null) {
            val address = known.address ?: return null
            return listOfNotNull(answer(packet, datagram, address))
        }
        // El DNS de la red suele contestar en milisegundos: se le espera un instante aquí mismo, y si tarda más, la
        // respuesta se manda cuando llegue (o la consulta vuelve al bucle) sin frenar el resto del tráfico.
        val resolving = scope.async {
            // Un fallo aquí no debe tumbar la sesión, que comparte este ámbito: el nombre sigue su camino de siempre.
            try {
                local.resolver.resolve(question.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.WARN, TAG, "Red local: no se pudo resolver ${question.name}: ${e.message}")
                null
            }
        }
        withTimeoutOrNull(INLINE_WAIT_MS) { resolving.join() }
        if (resolving.isCompleted) {
            val address = resolving.await() ?: return null
            return listOfNotNull(answer(packet, datagram, address))
        }
        scope.launch {
            val address = resolving.await()
            if (address != null) {
                answer(packet, datagram, address)?.let { output.writePacket(it) }
            } else {
                requeue(packet)
            }
        }
        return emptyList()
    }

    private fun answer(packet: Ipv4Packet, datagram: UdpDatagram, address: Int): ByteArray? =
        DnsResponder.reply(datagram.payload, address, LocalDnsResolver.ANSWER_TTL_SECONDS)?.let { reply(packet, datagram, it) }

    /** La respuesta vuelve desde el servidor al que se preguntó, hacia quien preguntó. */
    private fun reply(packet: Ipv4Packet, datagram: UdpDatagram, payload: ByteArray): ByteArray = buildUdpPacket(
        source = packet.destination,
        destination = packet.source,
        sourcePort = DNS_PORT,
        destinationPort = datagram.sourcePort,
        payload = payload,
    )

    private companion object {
        const val TAG = "Dns"
        const val DNS_PORT = 53
        const val INLINE_WAIT_MS = 50L
    }
}
