package org.uci.proxy.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.NON_PUBLIC_RANGES
import org.uci.proxy.core.packet.parseIpv4
import org.uci.proxy.core.packet.routesExcluding
import org.uci.proxy.core.port.HostResolver
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.port.TunnelUnavailableException
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.OpenVpnPush
import org.uci.proxy.core.vpn.VpnConnectResult
import org.uci.proxy.core.vpn.VpnExit
import org.uci.proxy.core.vpn.VpnStart

/** Estado del túnel, compartido entre quien lo ejecuta y quien lo observa dentro del mismo proceso. */
class TunnelStateStore : TunnelMonitor {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    fun set(newState: TunnelState) {
        mutableState.value = newState
    }
}

/**
 * Ejecuta un túnel de principio a fin: elige direcciones, crea la interfaz, corre el motor
 * y va publicando el estado.
 *
 * @param publicRoutes destinos de Internet que entran en el túnel. Por defecto, todo el IPv4 público:
 *   las redes privadas quedan fuera para que la red local y el propio proxy se alcancen directamente.
 */
class TunnelRunner(
    private val profiles: ProfileRepository,
    private val addressPlanner: TunAddressPlanner,
    private val engineFactory: TunnelEngineFactory,
    private val hostResolver: HostResolver,
    private val stateStore: TunnelStateStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val publicRoutes: List<Ipv4Route> = routesExcluding(NON_PUBLIC_RANGES),
    /** Cómo se llega a la VPN de un perfil con «Otro País». Sin él (o en iOS), esos perfiles no conectan. */
    private val vpnExit: VpnExit? = null,
) {
    /** Carga el perfil y su contraseña del almacén y ejecuta el túnel. */
    suspend fun run(platformTunnel: PlatformTunnel, profileId: String, settings: AppSettings = AppSettings()) {
        val profile = profiles.profiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            stateStore.set(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND))
            return
        }
        val vpn = if (profile.vpn == null) null else profiles.vpnSecrets(profileId)?.let { VpnStart(it.ovpn, profile.vpn.username, it.password) }
        if (profile.vpn != null && vpn == null) {
            logger.log(LogLevel.ERROR, TAG, "El perfil tiene una VPN pero no se pudo leer su archivo")
            stateStore.set(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID))
            return
        }
        run(platformTunnel, profile, profiles.password(profileId), settings, vpn)
    }

    /**
     * Suspende mientras el túnel esté activo. Termina cuando la interfaz se cierra o al cancelar,
     * y en ambos casos deja el estado en desconectado (o en fallo, si no llegó a arrancar).
     */
    suspend fun run(
        platformTunnel: PlatformTunnel,
        profile: ProxyProfile,
        password: String,
        settings: AppSettings = AppSettings(),
        /** Si no es null, el tráfico sale por esta VPN en vez de por el proxy directamente. */
        vpn: VpnStart? = null,
    ) {
        // Antes de tocar la red: con el túnel activo el DNS del equipo ya no devuelve direcciones reales.
        val proxyAddress = hostResolver.resolveIpv4(profile.host)
        if (proxyAddress == null) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo resolver el proxy ${profile.host}")
            stateStore.set(TunnelState.Failed(TunnelFailure.PROXY_HOST_UNRESOLVED))
            return
        }
        val resolvedProfile = profile.copy(host = proxyAddress)
        if (vpn != null) {
            runThroughVpn(platformTunnel, profile, resolvedProfile, password, vpn, settings)
            return
        }

        val plan = addressPlanner.plan()
        val tun = try {
            platformTunnel.establish(
                TunConfig(
                    sessionName = profile.name,
                    address = plan.tunAddressText,
                    dnsServer = plan.dnsServerText,
                    mtu = MTU,
                    routes = listOf(plan.subnet) + publicRoutes,
                    proxyAddress = proxyAddress,
                ),
            )
        } catch (e: TunnelUnavailableException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo crear la interfaz: ${e.message}")
            stateStore.set(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE))
            return
        }

        logger.log(LogLevel.INFO, TAG, "Túnel activo en ${plan.tunAddressText}, proxy $proxyAddress")
        stateStore.set(TunnelState.Connected(profile.id))
        try {
            coroutineScope {
                // En este ámbito viven también el temporizador TCP y los relés de cada conexión.
                val engine = engineFactory.create(plan, output = tun, scope = this, profile = resolvedProfile, password = password, settings = settings)
                val loop = launch(dispatchers.background) { engine.run(tun) }
                try {
                    loop.join()
                } catch (e: CancellationException) {
                    // La lectura de la interfaz es bloqueante y no atiende a la cancelación:
                    // solo cerrar la interfaz la despierta.
                    tun.close()
                    throw e
                }
                // La interfaz se cerró sola: se detiene lo que quede en marcha para que el ámbito termine.
                coroutineContext.cancelChildren()
            }
        } finally {
            tun.close()
            stateStore.set(TunnelState.Disconnected)
            logger.log(LogLevel.INFO, TAG, "Túnel detenido")
        }
    }

    /**
     * Modo «Otro País»: primero se establece la sesión VPN a través del proxy y solo entonces se crea la interfaz, con la
     * dirección y el DNS que el servidor indica. Después los paquetes pasan tal cual entre la interfaz y la VPN: no hace
     * falta la pila TCP ni el DNS falso, porque quien resuelve y enruta es el servidor VPN.
     */
    private suspend fun runThroughVpn(
        platformTunnel: PlatformTunnel,
        profile: ProxyProfile,
        resolvedProfile: ProxyProfile,
        password: String,
        vpn: VpnStart,
        settings: AppSettings,
    ) {
        val exit = vpnExit
        if (exit == null) {
            logger.log(LogLevel.ERROR, TAG, "Esta plataforma no admite el modo Otro País")
            stateStore.set(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID))
            return
        }
        val session = when (val result = exit.connect(resolvedProfile, password, vpn, settings)) {
            is VpnConnectResult.Connected -> result.session
            is VpnConnectResult.Failed -> {
                stateStore.set(TunnelState.Failed(result.failure))
                return
            }
        }

        val push = session.push
        val tun = try {
            platformTunnel.establish(
                TunConfig(
                    sessionName = profile.name,
                    address = push.address,
                    dnsServer = push.dnsServers.firstOrNull() ?: FALLBACK_DNS,
                    mtu = push.tunMtu,
                    routes = listOfNotNull(subnetOf(push)) + publicRoutes,
                    proxyAddress = resolvedProfile.host,
                    netmask = push.netmask,
                ),
            )
        } catch (e: TunnelUnavailableException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo crear la interfaz: ${e.message}")
            session.close()
            stateStore.set(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE))
            return
        }

        logger.log(LogLevel.INFO, TAG, "Túnel activo por la VPN en ${push.address}, DNS ${push.dnsServers.joinToString()}")
        stateStore.set(TunnelState.Connected(profile.id))
        try {
            coroutineScope {
                val outbound = launch(dispatchers.background) {
                    while (true) {
                        val packet = tun.readPacket() ?: break
                        // La VPN solo lleva IPv4: el IPv6 que el sistema intente se descarta, como en el modo directo.
                        if (packet.isNotEmpty() && (packet[0].toInt() ushr 4) == 4) session.writePacket(packet)
                    }
                }
                val inbound = launch { session.receive { tun.writePacket(it) } }
                try {
                    select<Unit> {
                        outbound.onJoin { }
                        inbound.onJoin { }
                    }
                } catch (e: CancellationException) {
                    // La lectura de la interfaz es bloqueante: solo cerrarla la despierta.
                    tun.close()
                    session.close()
                    throw e
                }
                tun.close()
                session.close()
                coroutineContext.cancelChildren()
            }
        } finally {
            tun.close()
            session.close()
            stateStore.set(TunnelState.Disconnected)
            logger.log(LogLevel.INFO, TAG, "Túnel detenido")
        }
    }

    /** La red de la VPN (por ejemplo 10.98.0.0/16): ahí vive su servidor DNS, que debe alcanzarse por la interfaz. */
    private fun subnetOf(push: OpenVpnPush): Ipv4Route? {
        val address = parseIpv4(push.address) ?: return null
        val mask = parseIpv4(push.netmask) ?: return null
        val prefix = mask.countOneBits()
        return Ipv4Route(address and mask, prefix)
    }

    private companion object {
        const val TAG = "Tunnel"
        const val MTU = 1500

        // Si el servidor VPN no empuja un DNS: una dirección pública, que de todos modos va por la VPN.
        const val FALLBACK_DNS = "1.1.1.1"
    }
}
