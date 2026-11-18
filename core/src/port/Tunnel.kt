package org.uci.proxy.core.port

import kotlinx.coroutines.flow.StateFlow
import org.uci.proxy.core.packet.Ipv4Route

interface PacketReader {
    /** Siguiente paquete IP que sale de las apps. Devuelve null cuando la interfaz se cerró. */
    suspend fun readPacket(): ByteArray?
}

fun interface PacketWriter {
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
    /** Servidor DNS que se anuncia a las apps; está dentro de la subred virtual y lo atiende el motor. */
    val dnsServer: String,
    val mtu: Int,
    /** Destinos cuyo tráfico entra en el túnel: la subred virtual del DNS falso y todo el IPv4 público. */
    val routes: List<Ipv4Route>,
    /**
     * Dirección IPv4 del proxy. Las conexiones hacia ella deben salir por la red real, nunca por el
     * túnel; cada plataforma lo garantiza a su manera.
     */
    val proxyAddress: String,
    /** Máscara de la dirección de la interfaz. Con la VPN la fija el servidor; sin ella, la subred virtual (/16). */
    val netmask: String = "255.255.0.0",
)

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

    /** El nombre del servidor proxy no se pudo resolver a una dirección. */
    PROXY_HOST_UNRESOLVED,

    /** El proxy rechazó el usuario o la contraseña del perfil. */
    PROXY_AUTH_FAILED,

    /** El archivo `.ovpn` del perfil no sirve (UDP, otra función no admitida) o falta. */
    VPN_PROFILE_INVALID,

    /** No se pudo llegar al servidor VPN a través del proxy. */
    VPN_SERVER_UNREACHABLE,

    /** El proxy respondió, pero su política no deja llegar a ningún servidor del perfil (403 o similar). */
    VPN_SERVER_BLOCKED,

    /** El servidor VPN rechazó el usuario o la contraseña. */
    VPN_AUTH_FAILED,

    /** El handshake con el servidor VPN falló por otro motivo. */
    VPN_HANDSHAKE_FAILED,
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

/**
 * Resuelve el nombre del proxy con el DNS real, antes de levantar el túnel.
 *
 * Una vez activo el túnel el DNS del equipo devuelve direcciones inventadas, así que el motor
 * tiene que conectar con el proxy por su dirección y no por su nombre.
 */
fun interface HostResolver {
    /** @return la dirección IPv4 en notación con puntos, o null si el nombre no resuelve. */
    suspend fun resolveIpv4(host: String): String?
}

/** Direcciones IPv4 de las interfaces de red del equipo, para no elegir una subred que ya esté en uso. */
fun interface LocalAddresses {
    fun ipv4(): List<Int>
}
