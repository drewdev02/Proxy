package org.uci.proxy.core.port

import kotlinx.coroutines.flow.StateFlow

interface PacketReader {
    /** Siguiente paquete IP que sale de las apps. Devuelve null cuando la interfaz se cerró. */
    suspend fun readPacket(): ByteArray?
}

interface PacketWriter {
    /** Entrega un paquete IP a las apps, como si llegara de la red. */
    suspend fun writePacket(packet: ByteArray)
}

/** La interfaz de red virtual por la que pasa el tráfico capturado. */
interface TunDevice : PacketReader, PacketWriter {
    /** Desbloquea cualquier lectura en curso y libera la interfaz. Se puede llamar más de una vez. */
    fun close()
}

/** Cómo debe configurarse la interfaz. Las direcciones van en notación con puntos. */
data class TunConfig(
    /** Nombre que el sistema muestra para la conexión. */
    val sessionName: String,
    /** Dirección de la propia interfaz. */
    val address: String,
    /** Red /16 que se enruta al túnel: de ahí salen las direcciones del DNS falso. */
    val virtualSubnet: String,
    /** Servidor DNS que se anuncia a las apps; está dentro de [virtualSubnet] y lo atiende el motor. */
    val dnsServer: String,
    val mtu: Int,
) {
    companion object {
        const val VIRTUAL_SUBNET_PREFIX = 16
    }
}

class TunnelUnavailableException(message: String) : Exception(message)

/** Crea la interfaz de red virtual con el mecanismo de cada sistema. */
fun interface PlatformTunnel {
    /** @throws TunnelUnavailableException si el sistema no permite crear la interfaz. */
    suspend fun establish(config: TunConfig): TunDevice
}

enum class TunnelFailure {
    /** La persona no autorizó a la app a crear una conexión VPN. */
    VPN_PERMISSION_DENIED,

    /** Falta el permiso de red local, sin el cual no se alcanza el proxy. */
    LOCAL_NETWORK_DENIED,

    /** El perfil elegido ya no existe. */
    PROFILE_NOT_FOUND,

    /** El sistema no permitió crear la interfaz. */
    INTERFACE_UNAVAILABLE,
}

sealed interface TunnelState {
    data object Disconnected : TunnelState

    data class Connecting(val profileId: String) : TunnelState

    data class Connected(val profileId: String) : TunnelState

    data class Failed(val failure: TunnelFailure) : TunnelState
}

/** Lo que la interfaz de usuario usa para mandar. */
interface TunnelControl {
    /** False en las plataformas donde el túnel todavía no existe; la interfaz oculta entonces sus controles. */
    val isAvailable: Boolean

    suspend fun connect(profileId: String)

    suspend fun disconnect()
}

/** Lo que la interfaz de usuario usa para observar. */
interface TunnelMonitor {
    val state: StateFlow<TunnelState>
}

/** Direcciones IPv4 de las interfaces de red del equipo, para no elegir una subred que ya esté en uso. */
fun interface LocalAddresses {
    fun ipv4(): List<Int>
}
