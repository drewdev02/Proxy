package org.uci.proxy.core.port

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.uci.proxy.core.proxy.ProtocolIssue
import org.uci.proxy.core.vpn.OvpnConfigError
import org.uci.proxy.core.vpn.VpnHandshakeIssue
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
    /** Apps (paquetes de Android) que no entran en el túnel y salen por la red real. Solo Android lo aplica. */
    val excludedApps: List<String> = emptyList(),
)

class TunnelUnavailableException(message: String) : Exception(message)

/** Crea la interfaz de red virtual con el mecanismo de cada sistema. */
fun interface PlatformTunnel {
    /** @throws TunnelUnavailableException si el sistema no permite crear la interfaz. */
    suspend fun establish(config: TunConfig): TunDevice
}

/**
 * Por qué no se pudo conectar. Cada caso es lo más concreto que se sabe, con los datos que lo explican; la interfaz
 * elige la frase y qué ofrecer para arreglarlo. Viaja entre procesos en escritorio, así que es serializable.
 */
@Serializable
sealed interface TunnelFailure {
    // Permisos y sistema

    /** La persona no autorizó a la app a crear una conexión VPN (o, en escritorio, a correr el motor como administrador). */
    @Serializable @SerialName("vpn_permission_denied")
    data object VpnPermissionDenied : TunnelFailure

    /** Falta el permiso de red local, sin el cual no se alcanza el proxy. */
    @Serializable @SerialName("local_network_denied")
    data object LocalNetworkDenied : TunnelFailure

    /** El sistema no permitió crear la interfaz (en Android, por ejemplo, otra app con VPN siempre activa). */
    @Serializable @SerialName("interface_unavailable")
    data object InterfaceUnavailable : TunnelFailure

    /** El motor del túnel (un proceso aparte en escritorio) se detuvo sin avisar. */
    @Serializable @SerialName("engine_stopped")
    data object EngineStopped : TunnelFailure

    // Perfil

    /** El perfil elegido ya no existe. */
    @Serializable @SerialName("profile_not_found")
    data object ProfileNotFound : TunnelFailure

    // Red y proxy

    /** El equipo no tiene red. */
    @Serializable @SerialName("no_network")
    data object NoNetwork : TunnelFailure

    /** El nombre del servidor proxy no se pudo resolver a una dirección. */
    @Serializable @SerialName("proxy_host_unresolved")
    data class ProxyHostUnresolved(val host: String) : TunnelFailure

    /** No se llegó al proxy. */
    @Serializable @SerialName("proxy_unreachable")
    data class ProxyUnreachable(val kind: NetFailure) : TunnelFailure

    /** El proxy pide usuario y contraseña y el perfil no tiene. */
    @Serializable @SerialName("proxy_auth_required")
    data object ProxyAuthRequired : TunnelFailure

    /** El proxy rechazó el usuario o la contraseña del perfil. */
    @Serializable @SerialName("proxy_auth_failed")
    data object ProxyAuthFailed : TunnelFailure

    /** El proxy solo ofrece esquemas de autenticación que la app no implementa. */
    @Serializable @SerialName("proxy_auth_unsupported")
    data class ProxyAuthUnsupported(val schemes: List<String>) : TunnelFailure

    /** El servidor del perfil no habla el protocolo elegido (HTTP o SOCKS5). */
    @Serializable @SerialName("proxy_protocol_mismatch")
    data class ProxyProtocolMismatch(val issue: ProtocolIssue) : TunnelFailure

    // Otro País: configuración

    /** El perfil sale por Otro País, pero sus secretos (el .ovpn, la contraseña o la clave) no se pudieron leer. */
    @Serializable @SerialName("vpn_secrets_missing")
    data object VpnSecretsMissing : TunnelFailure

    /** El archivo .ovpn del perfil no sirve para este cliente. */
    @Serializable @SerialName("ovpn_invalid")
    data class OvpnInvalid(val error: OvpnConfigError) : TunnelFailure

    /** La configuración de Tailscale no es válida (la dirección del servidor de control, por ejemplo). */
    @Serializable @SerialName("tailscale_config_invalid")
    data object TailscaleConfigInvalid : TunnelFailure

    /** El motor de Otro País del perfil no está disponible en este equipo. */
    @Serializable @SerialName("engine_unavailable")
    data object EngineUnavailable : TunnelFailure

    // Otro País: conexión

    /** No se pudo llegar al servidor de Otro País a través del proxy. */
    @Serializable @SerialName("vpn_server_unreachable")
    data object VpnServerUnreachable : TunnelFailure

    /** El proxy respondió, pero su política no deja llegar a ningún servidor del perfil (403 o similar). */
    @Serializable @SerialName("vpn_server_blocked")
    data object VpnServerBlocked : TunnelFailure

    /** El servidor de Otro País rechazó las credenciales (usuario y contraseña de OpenVPN, clave de Tailscale). */
    @Serializable @SerialName("vpn_auth_failed")
    data object VpnAuthFailed : TunnelFailure

    /** La clave de autenticación de Tailscale caducó. */
    @Serializable @SerialName("tailscale_key_expired")
    data object TailscaleKeyExpired : TunnelFailure

    /** El handshake con el servidor OpenVPN falló; [subject] concreta el motivo (el cifrado, lo que dijo el servidor). */
    @Serializable @SerialName("vpn_handshake_failed")
    data class VpnHandshakeFailed(val issue: VpnHandshakeIssue, val subject: String? = null) : TunnelFailure

    /** Ya hay una sesión de Tailscale abierta en la app. */
    @Serializable @SerialName("tailscale_busy")
    data object TailscaleBusy : TunnelFailure

    /** El nodo de salida elegido ([name]) no existe en la red de Tailscale. */
    @Serializable @SerialName("exit_node_not_found")
    data class ExitNodeNotFound(val name: String) : TunnelFailure

    /** La red de Tailscale tiene varios nodos de salida y el perfil no eligió ninguno. */
    @Serializable @SerialName("exit_node_not_chosen")
    data object ExitNodeNotChosen : TunnelFailure

    /** Un fallo interno del motor de Otro País. */
    @Serializable @SerialName("engine_internal")
    data object EngineInternal : TunnelFailure
}

/**
 * Un código corto y estable por fallo: se muestra en la interfaz y se escribe en el registro, para encontrar el error
 * en él a partir de una captura.
 */
val TunnelFailure.code: String
    get() = when (this) {
        TunnelFailure.VpnPermissionDenied -> "SYS-VPN-PERMISSION"
        TunnelFailure.LocalNetworkDenied -> "SYS-LAN-PERMISSION"
        TunnelFailure.InterfaceUnavailable -> "SYS-INTERFACE"
        TunnelFailure.EngineStopped -> "SYS-ENGINE"
        TunnelFailure.ProfileNotFound -> "PRF-MISSING"
        TunnelFailure.NoNetwork -> "NET-OFFLINE"
        is TunnelFailure.ProxyHostUnresolved -> "PX-DNS"
        is TunnelFailure.ProxyUnreachable -> "PX-NET-${kind.name}"
        TunnelFailure.ProxyAuthRequired -> "PX-AUTH-REQUIRED"
        TunnelFailure.ProxyAuthFailed -> "PX-AUTH"
        is TunnelFailure.ProxyAuthUnsupported -> "PX-AUTH-SCHEME"
        is TunnelFailure.ProxyProtocolMismatch -> "PX-PROTO-${issue.name}"
        TunnelFailure.VpnSecretsMissing -> "VPN-SECRETS"
        is TunnelFailure.OvpnInvalid -> "VPN-OVPN-${error::class.simpleName?.uppercase()}"
        TunnelFailure.TailscaleConfigInvalid -> "TS-CONFIG"
        TunnelFailure.EngineUnavailable -> "VPN-ENGINE"
        TunnelFailure.VpnServerUnreachable -> "VPN-UNREACHABLE"
        TunnelFailure.VpnServerBlocked -> "VPN-BLOCKED"
        TunnelFailure.VpnAuthFailed -> "VPN-AUTH"
        TunnelFailure.TailscaleKeyExpired -> "TS-KEY-EXPIRED"
        is TunnelFailure.VpnHandshakeFailed -> "VPN-HANDSHAKE-${issue.name}"
        TunnelFailure.TailscaleBusy -> "TS-BUSY"
        is TunnelFailure.ExitNodeNotFound -> "TS-EXIT-NOT-FOUND"
        TunnelFailure.ExitNodeNotChosen -> "TS-EXIT-NOT-CHOSEN"
        TunnelFailure.EngineInternal -> "VPN-INTERNAL"
    }

/** Lo que le pasa al proxy con el túnel ya conectado, según cómo le va a cada conexión. */
enum class ProxyProblem {
    /** El proxy rechaza el usuario o la contraseña (por ejemplo, porque se cambió la contraseña). */
    AUTH_REJECTED,

    /** El proxy no responde o no se llega a él. */
    UNREACHABLE,
}

sealed interface TunnelState {
    data object Disconnected : TunnelState

    data class Connecting(val profileId: String) : TunnelState

    /**
     * @param vpnDown con reglas de salida que usan «Otro País»: la VPN no está (no conectó o se cayó), así que lo que iba
     *   por ella sale por el proxy mientras se reintenta.
     * @param viaProxy con la VPN en pie, los nombres que debían salir por ella pero salen por el proxy porque su DNS no
     *   los resolvió (los últimos, sin repetir). Se vacía cuando la VPN vuelve a conectar.
     */
    data class Connected(
        val profileId: String,
        val vpnDown: Boolean = false,
        /** Con [vpnDown]: lo que iba por Otro País se bloquea en vez de salir por el proxy (el ajuste está apagado). */
        val vpnBlocked: Boolean = false,
        val viaProxy: List<String> = emptyList(),
        /** El túnel sigue en pie, pero el proxy está fallando: lo que pasa por él no carga. */
        val proxyProblem: ProxyProblem? = null,
    ) : TunnelState

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
