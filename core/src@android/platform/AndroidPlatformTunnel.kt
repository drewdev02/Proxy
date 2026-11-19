package org.uci.proxy.core.platform

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.port.TunnelUnavailableException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crea la interfaz TUN con [VpnService.Builder]. Necesita el servicio que el sistema ya arrancó,
 * por eso lo construye el propio servicio y no Koin.
 */
class AndroidPlatformTunnel(
    private val service: VpnService,
    private val ioDispatcher: CoroutineDispatcher,
) : PlatformTunnel {
    override suspend fun establish(config: TunConfig): TunDevice {
        val builder = service.Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.address, ADDRESS_PREFIX)
            .addDnsServer(config.dnsServer)
            // Lecturas bloqueantes: sin esto read() devuelve 0 al instante y habría que sondear.
            .setBlocking(true)
            // La propia app queda fuera del túnel: sus conexiones al proxy salen por la red real.
            .addDisallowedApplication(service.packageName)
        config.routes.forEach { builder.addRoute(it.addressText, it.prefixLength) }
        // Las apps con salida «Directo»: el sistema ni las mete en la VPN. Una que ya no esté instalada se salta.
        config.excludedApps.filter { it != service.packageName }.forEach { app ->
            try {
                builder.addDisallowedApplication(app)
            } catch (_: PackageManager.NameNotFoundException) {
                // Desinstalada desde que se creó la regla.
            }
        }

        // establish() devuelve null si la app dejó de ser la VPN preparada (otra app tomó el relevo).
        val descriptor = builder.establish()
            ?: throw TunnelUnavailableException("El sistema no concedió la interfaz VPN")
        return AndroidTunDevice(descriptor, config.mtu, ioDispatcher)
    }

    private companion object {
        const val ADDRESS_PREFIX = 32
    }
}

private class AndroidTunDevice(
    private val descriptor: ParcelFileDescriptor,
    mtu: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : TunDevice {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val buffer = ByteArray(mtu)

    override suspend fun readPacket(): ByteArray? = withContext(ioDispatcher) {
        try {
            val count = input.read(buffer)
            if (count < 0) null else buffer.copyOf(count)
        } catch (_: IOException) {
            // Así termina la lectura cuando close() cierra el descriptor desde otro hilo.
            null
        }
    }

    override suspend fun writePacket(packet: ByteArray) = withContext(ioDispatcher) {
        try {
            output.write(packet)
        } catch (_: IOException) {
            // La interfaz se cerró mientras se respondía: el bucle de lectura lo detectará.
        }
    }

    override fun close() {
        try {
            descriptor.close()
        } catch (_: IOException) {
            // Ya estaba cerrada.
        }
    }
}
