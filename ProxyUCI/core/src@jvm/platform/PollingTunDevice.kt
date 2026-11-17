package org.uci.proxy.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.uci.proxy.core.port.TunDevice

/** Por donde pasan los paquetes IP de una interfaz virtual del sistema (utun en macOS, Wintun en Windows). */
interface PacketChannel {
    /** Espera hasta [timeoutMs] y lee un paquete IPv4. Devuelve null si no llegó nada o si se cerró. */
    fun read(timeoutMs: Int): ByteArray?

    fun write(packet: ByteArray)

    /** Cierra el canal. Puede llamarse varias veces. */
    fun close()
}

/**
 * [TunDevice] sobre un [PacketChannel] bloqueante. Se sondea con tope en vez de bloquear sin límite:
 * así el cierre desde otra corrutina se atiende pronto.
 *
 * @param onClose limpieza del adaptador de cada sistema; se ejecuta una sola vez, tras cerrar el canal.
 */
class PollingTunDevice(
    private val channel: PacketChannel,
    private val ioDispatcher: CoroutineDispatcher,
    private val onClose: () -> Unit,
) : TunDevice {
    @Volatile
    private var closed = false

    override suspend fun readPacket(): ByteArray? = withContext(ioDispatcher) {
        while (!closed) {
            channel.read(POLL_MS)?.let { return@withContext it }
            yield()
        }
        null
    }

    override suspend fun writePacket(packet: ByteArray) = withContext(ioDispatcher) {
        channel.write(packet)
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.close()
        onClose()
    }

    private companion object {
        const val POLL_MS = 250
    }
}
