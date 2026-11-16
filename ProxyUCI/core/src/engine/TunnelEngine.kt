package org.uci.proxy.core.engine

import org.uci.proxy.core.dns.DnsResponder
import org.uci.proxy.core.dns.FakeDnsTable
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.UdpDatagram
import org.uci.proxy.core.packet.buildUdpPacket
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TunDevice

/**
 * Atiende un tipo de tráfico. Añadir un protocolo (TCP en la capa siguiente) es escribir otro
 * manejador y registrarlo; [TunnelEngine] no cambia.
 */
fun interface PacketHandler {
    /**
     * @return los paquetes que hay que devolver a las apps, o null si [packet] no es de este manejador.
     */
    fun handle(packet: Ipv4Packet): List<ByteArray>?
}

/** Contesta las consultas DNS (UDP al puerto 53) con direcciones de la tabla falsa. */
class DnsPacketHandler(
    private val responder: DnsResponder,
    private val logger: Logger,
) : PacketHandler {
    override fun handle(packet: Ipv4Packet): List<ByteArray>? {
        val datagram = UdpDatagram.parse(packet) ?: return null
        if (datagram.destinationPort != DNS_PORT) return null

        val query = datagram.payload
        val answer = responder.respond(query) ?: return emptyList()
        responder.parseQuestion(query)?.first?.let { question ->
            logger.log(LogLevel.DEBUG, TAG, "DNS tipo ${question.type}: ${question.name}")
        }
        // La respuesta vuelve desde el servidor al que se preguntó, hacia quien preguntó.
        return listOf(
            buildUdpPacket(
                source = packet.destination,
                destination = packet.source,
                sourcePort = DNS_PORT,
                destinationPort = datagram.sourcePort,
                payload = answer,
            ),
        )
    }

    private companion object {
        const val TAG = "Dns"
        const val DNS_PORT = 53
    }
}

/** Crea el motor de una sesión, que depende de las direcciones elegidas para ese túnel. */
class TunnelEngineFactory(private val logger: Logger) {
    fun create(plan: TunAddressPlan): TunnelEngine {
        val dns = DnsPacketHandler(DnsResponder(FakeDnsTable(plan.subnetBase)), logger)
        return TunnelEngine(handlers = listOf(dns))
    }
}

/**
 * Bucle del túnel: lee cada paquete de la interfaz, se lo ofrece a los manejadores por orden
 * y escribe lo que el primero interesado devuelva. Lo que nadie reclama se descarta.
 */
class TunnelEngine(private val handlers: List<PacketHandler>) {
    /** Corre hasta que la interfaz se cierra. */
    suspend fun run(tun: TunDevice) {
        while (true) {
            val bytes = tun.readPacket() ?: return
            val packet = Ipv4Packet.parse(bytes) ?: continue
            val replies = handlers.firstNotNullOfOrNull { it.handle(packet) } ?: continue
            replies.forEach { tun.writePacket(it) }
        }
    }
}
