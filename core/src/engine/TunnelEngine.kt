package org.uci.proxy.core.engine

import org.uci.proxy.core.engine.handler.LocalDnsHandler
import org.uci.proxy.core.engine.handler.PacketHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TunDevice

/**
 * Bucle del túnel: lee cada paquete de la interfaz, se lo ofrece a los manejadores por orden
 * y escribe lo que el primero interesado devuelva. Lo que nadie reclama se descarta.
 *
 * @param requeued paquetes que un manejador apartó un momento y devuelve al bucle (ver [LocalDnsHandler]). Se atienden
 *   antes del siguiente paquete de la interfaz, en este mismo bucle: así los manejadores siguen sin compartir estado
 *   entre hilos.
 */
class TunnelEngine(
    private val handlers: List<PacketHandler>,
    private val requeued: ReceiveChannel<Ipv4Packet>? = null,
    private val logger: Logger? = null,
) {
    /** Corre hasta que la interfaz se cierra. */
    suspend fun run(tun: TunDevice) {
        while (true) {
            val bytes = tun.readPacket() ?: return
            while (true) {
                val waiting = requeued?.tryReceive()?.getOrNull() ?: break
                firstReply(waiting)?.forEach { tun.writePacket(it) }
            }
            val packet = Ipv4Packet.parse(bytes) ?: continue
            val replies = firstReply(packet) ?: continue
            replies.forEach { tun.writePacket(it) }
        }
    }

    /** Un manejador que lanza pierde ese paquete, pero no termina el motor: con él caería todo el túnel. */
    private suspend fun firstReply(packet: Ipv4Packet): List<ByteArray>? = try {
        handlers.firstNotNullOfOrNull { it.handle(packet) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger?.log(LogLevel.ERROR, "Motor", "Error al atender un paquete: ${e.message}")
        null
    }
}
