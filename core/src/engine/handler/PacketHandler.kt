package org.uci.proxy.core.engine.handler

import org.uci.proxy.core.engine.TunnelEngine
import org.uci.proxy.core.packet.Ipv4Packet

/**
 * Atiende un tipo de tráfico. Añadir un protocolo es escribir otro manejador y registrarlo;
 * [TunnelEngine] no cambia.
 */
fun interface PacketHandler {
    /**
     * @return los paquetes que hay que devolver a las apps, o null si [packet] no es de este manejador.
     */
    suspend fun handle(packet: Ipv4Packet): List<ByteArray>?
}
