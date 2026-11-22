package org.uci.proxy.core.fakes

import kotlinx.coroutines.channels.Channel
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.port.TunnelUnavailableException

/** Interfaz TUN en memoria: la prueba inyecta paquetes con [inject] y lee en [written] lo que el motor devuelve. */
class FakeTunDevice : TunDevice {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<ByteArray>()
    var closed = false
        private set

    fun inject(packet: ByteArray) {
        incoming.trySend(packet)
    }

    override suspend fun readPacket(): ByteArray? = incoming.receiveCatching().getOrNull()

    override suspend fun writePacket(packet: ByteArray) {
        written += packet
    }

    override fun close() {
        closed = true
        incoming.close()
    }
}

class FakePlatformTunnel(private val device: FakeTunDevice = FakeTunDevice()) : PlatformTunnel {
    var failWith: String? = null
    var config: TunConfig? = null
        private set
    val tun: FakeTunDevice get() = device

    override suspend fun establish(config: TunConfig): TunDevice {
        failWith?.let { throw TunnelUnavailableException(it) }
        this.config = config
        return device
    }
}
