package org.uci.proxy.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.uci.proxy.core.dns.LocalDnsPolicy
import org.uci.proxy.core.dns.LocalDnsResolver
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.packet.NON_PUBLIC_RANGES
import org.uci.proxy.core.packet.routesExcluding
import org.uci.proxy.core.port.DnsTransport
import org.uci.proxy.core.port.HostResolver
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformTunnel
import org.uci.proxy.core.port.TunConfig
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.port.TunnelUnavailableException
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.port.TunDevice
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.routing.RuleSet
import org.uci.proxy.core.vpn.VpnSession
import kotlin.time.Duration.Companion.seconds
import org.uci.proxy.core.port.EngineStateDirectories
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.CountryExitStart
import org.uci.proxy.core.vpn.ExitContext
import org.uci.proxy.core.vpn.VpnConnectResult

/** Estado del túnel, compartido entre quien lo ejecuta y quien lo observa dentro del mismo proceso. */
class TunnelStateStore : TunnelMonitor {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    override val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    fun set(newState: TunnelState) {
        mutableState.value = newState
    }

    /** Cambia el estado a partir del actual sin perder un cambio que llegue a la vez desde otra corrutina. */
    fun update(change: (TunnelState) -> TunnelState) {
        mutableState.update(change)
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
    /** Los motores de «Otro País» de esta plataforma. Sin ninguno, esos perfiles no conectan. */
    private val engines: CountryEngines = CountryEngines.None,
    /** Dónde guarda cada perfil el estado de su motor (la identidad de un nodo de Tailscale). */
    private val stateDirectories: EngineStateDirectories = EngineStateDirectories.None,
    /** El DNS de la red real, para abrir sin proxy lo interno. Sin él (o sin [dnsTransport]), todo va como siempre. */
    private val localDnsSource: LocalDnsSource = LocalDnsSource.None,
    private val dnsTransport: DnsTransport? = null,
) {
    /** Carga el perfil y su contraseña del almacén y ejecuta el túnel. */
    suspend fun run(platformTunnel: PlatformTunnel, profileId: String, settings: AppSettings = AppSettings()) {
        val profile = profiles.profiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            stateStore.set(TunnelState.Failed(TunnelFailure.PROFILE_NOT_FOUND))
            return
        }
        val exit = profile.countryExit
        val start = if (exit == null) null else profiles.exitSecrets(profileId)?.let { CountryExitStart(exit, it, stateDirectories.forProfile(profileId)) }
        if (exit != null && start == null) {
            logger.log(LogLevel.ERROR, TAG, "El perfil sale por Otro País pero no se pudieron leer sus secretos")
            stateStore.set(TunnelState.Failed(TunnelFailure.VPN_PROFILE_INVALID))
            return
        }
        run(platformTunnel, profile, profiles.password(profileId), settings, start)
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
        /** Si no es null, el tráfico sale por «Otro País» con este motor en vez de por el proxy directamente. */
        vpn: CountryExitStart? = null,
    ) {
        // Antes de tocar la red: con el túnel activo el DNS del equipo ya no devuelve direcciones reales.
        val proxyAddress = hostResolver.resolveIpv4(profile.host)
        if (proxyAddress == null) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo resolver el proxy ${profile.host}")
            stateStore.set(TunnelState.Failed(TunnelFailure.PROXY_HOST_UNRESOLVED))
            return
        }
        val resolvedProfile = profile.copy(host = proxyAddress)
        val routing = profile.routing
        // También antes de tocar la red: el DNS de la red real deja de verse en cuanto el túnel captura el DNS.
        val localDnsConfig = readLocalDns()
        if (vpn != null && !routing.enabled) {
            runThroughVpn(platformTunnel, profile, resolvedProfile, password, vpn, settings, localDnsConfig)
            return
        }

        // Con reglas de salida que usan «Otro País», la VPN se abre antes que la interfaz: así las primeras consultas DNS
        // ya pueden ir por ella. Si no conecta, el túnel arranca igual y lo suyo sale por el proxy mientras se reintenta.
        val rules = if (routing.enabled) RuleSet(routing) else null
        val vpnStart = if (routing.usesVpn) vpn else null
        if (routing.usesVpn && vpnStart == null) {
            logger.log(LogLevel.WARN, TAG, "Las reglas mandan tráfico por Otro País, pero el perfil no tiene VPN: sale por el proxy")
        }
        val retry = vpnStart?.let { VpnRetry(ExitContext(resolvedProfile, password, settings), it) }
        val firstAttempt = retry?.let { openVpn(it) }
        val firstSession = firstAttempt?.session

        val plan = addressPlanner.plan()
        val directRoutes = rules?.directRoutes.orEmpty()
        val tun = try {
            platformTunnel.establish(
                TunConfig(
                    sessionName = profile.name,
                    address = plan.tunAddressText,
                    dnsServer = plan.dnsServerText,
                    mtu = MTU,
                    // Los rangos con salida directa no entran en la interfaz: el sistema los manda por la red real.
                    routes = listOf(plan.subnet) + if (directRoutes.isEmpty()) publicRoutes else routesExcluding(directRoutes, from = publicRoutes),
                    proxyAddress = proxyAddress,
                    excludedApps = rules?.directApps.orEmpty(),
                ),
            )
        } catch (e: TunnelUnavailableException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo crear la interfaz: ${e.message}")
            firstSession?.close()
            stateStore.set(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE))
            return
        }

        val link = VpnLink(plan.tunAddress, logger, onViaProxy = { host -> noteViaProxy(host) })
        val split = rules?.let { SplitRouting(it, link) }
        val localDns = localDnsFor(localDnsConfig, settings, rules, reserved = listOf(plan.subnet))
        logger.log(LogLevel.INFO, TAG, "Túnel activo en ${plan.tunAddressText}, proxy $proxyAddress" + if (rules != null) ", con reglas de salida" else "")
        val vpnDown = retry != null && firstSession == null
        if (vpnDown) logger.log(LogLevel.WARN, TAG, "Otro País no conectó: lo que iba por él sale por el proxy")
        stateStore.set(TunnelState.Connected(profile.id, vpnDown = vpnDown))
        try {
            coroutineScope {
                // En este ámbito viven también el temporizador TCP y los relés de cada conexión.
                val engine = engineFactory.create(plan, output = tun, scope = this, profile = resolvedProfile, password = password, settings = settings, split = split, localDns = localDns)
                val loop = launch(dispatchers.background) { engine.run(tun) }
                if (retry != null && firstAttempt != null) {
                    launch { superviseVpn(firstAttempt, link, tun, profile.id, retry) }
                }
                try {
                    loop.join()
                } catch (e: CancellationException) {
                    // La lectura de la interfaz es bloqueante y no atiende a la cancelación:
                    // solo cerrar la interfaz la despierta.
                    tun.close()
                    link.closeSession()
                    throw e
                }
                // La interfaz se cerró sola: se detiene lo que quede en marcha para que el ámbito termine.
                coroutineContext.cancelChildren()
            }
        } finally {
            tun.close()
            link.closeSession()
            stateStore.set(TunnelState.Disconnected)
            logger.log(LogLevel.INFO, TAG, "Túnel detenido")
        }
    }

    /** Lo necesario para volver a abrir la VPN. */
    /** Apunta que [host] sale por el proxy en vez de por Otro País, para que la interfaz lo diga. */
    private fun noteViaProxy(host: String) = stateStore.update { state ->
        if (state !is TunnelState.Connected || state.vpnDown) return@update state
        state.copy(viaProxy = (state.viaProxy - host + host).takeLast(MAX_VIA_PROXY))
    }

    private class VpnRetry(val context: ExitContext, val start: CountryExitStart)

    /** Un intento de abrir la VPN: la sesión, o si merece la pena reintentar. */
    private class VpnAttempt(val session: VpnSession?, val retry: Boolean)

    private suspend fun openVpn(retry: VpnRetry): VpnAttempt =
        when (val result = engines.connect(retry.context, retry.start)) {
            is VpnConnectResult.Connected -> VpnAttempt(result.session, retry = true)
            is VpnConnectResult.Failed -> {
                logger.log(LogLevel.WARN, TAG, "Otro País no conectó: ${result.failure}")
                // Credenciales o configuración malas no se arreglan reintentando.
                val retryable = !engines.isPermanent(retry.start.exit.kind, result.failure)
                if (!retryable) logger.log(LogLevel.ERROR, TAG, "La VPN no se reintentará: revisa el archivo o las credenciales del perfil")
                VpnAttempt(null, retryable)
            }
        }

    /**
     * Mantiene la VPN de las reglas de salida: la entrega al motor mientras está, y cuando se cae (o no llegó a conectar)
     * avisa, deja que su tráfico salga por el proxy y reintenta cada vez más espaciado.
     */
    private suspend fun superviseVpn(firstAttempt: VpnAttempt, link: VpnLink, tun: TunDevice, profileId: String, retry: VpnRetry) {
        val first = firstAttempt.session
        var session = first
        var retryable = firstAttempt.retry
        var wait = VPN_RETRY_FIRST
        try {
            while (true) {
                if (session != null) {
                    link.attach(session)
                    if (session !== first) {
                        logger.log(LogLevel.INFO, TAG, "Otro País volvió: su tráfico sale otra vez por la VPN")
                        stateStore.set(TunnelState.Connected(profileId))
                    }
                    wait = VPN_RETRY_FIRST
                    try {
                        session.receive { packet -> link.inbound(packet)?.let { tun.writePacket(it) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.log(LogLevel.WARN, TAG, "La VPN falló: ${e.message}")
                    }
                    link.detach()
                    session.close()
                    session = null
                    logger.log(LogLevel.WARN, TAG, "Se cayó Otro País: su tráfico sale por el proxy mientras se reintenta")
                    stateStore.set(TunnelState.Connected(profileId, vpnDown = true))
                }
                if (!retryable) return
                delay(wait)
                wait = minOf(wait * 2, VPN_RETRY_MAX)
                val attempt = openVpn(retry)
                session = attempt.session
                retryable = attempt.retry
            }
        } finally {
            session?.close()
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
        vpn: CountryExitStart,
        settings: AppSettings,
        localDnsConfig: LocalDnsConfig = LocalDnsConfig.NONE,
    ) {
        val session = when (val result = engines.connect(ExitContext(resolvedProfile, password, settings), vpn)) {
            is VpnConnectResult.Connected -> result.session
            is VpnConnectResult.Failed -> {
                stateStore.set(TunnelState.Failed(result.failure))
                return
            }
        }

        val network = session.network
        val tun = try {
            platformTunnel.establish(
                TunConfig(
                    sessionName = profile.name,
                    address = network.address,
                    dnsServer = network.dnsServers.firstOrNull() ?: FALLBACK_DNS,
                    mtu = network.mtu,
                    routes = listOfNotNull(network.subnet) + publicRoutes,
                    proxyAddress = resolvedProfile.host,
                    netmask = network.netmask,
                ),
            )
        } catch (e: TunnelUnavailableException) {
            logger.log(LogLevel.ERROR, TAG, "No se pudo crear la interfaz: ${e.message}")
            session.close()
            stateStore.set(TunnelState.Failed(TunnelFailure.INTERFACE_UNAVAILABLE))
            return
        }

        logger.log(LogLevel.INFO, TAG, "Túnel activo por Otro País (${vpn.exit.kind.tag}) en ${network.address}, DNS ${network.dnsServers.joinToString()}")
        stateStore.set(TunnelState.Connected(profile.id))
        try {
            coroutineScope {
                // Aquí no hay motor que reparta: el DNS de la red local se atiende al vuelo, y lo que no le toca sigue a
                // la VPN tal cual. Lo que apartó un momento vuelve por esta misma cola, antes del siguiente paquete.
                val localDns = localDnsFor(localDnsConfig, settings, rules = null, reserved = listOfNotNull(network.subnet))
                val requeued = Channel<Ipv4Packet>(REQUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
                val local = LocalDnsHandler(localDns, tun, this, logger) { requeued.trySend(it) }
                val outbound = launch(dispatchers.background) {
                    while (true) {
                        val packet = tun.readPacket() ?: break
                        while (true) {
                            val waiting = requeued.tryReceive().getOrNull() ?: break
                            session.writePacket(waiting.bytes)
                        }
                        // La VPN solo lleva IPv4: el IPv6 que el sistema intente se descarta, como en el modo directo.
                        if (packet.isEmpty() || (packet[0].toInt() ushr 4) != 4) continue
                        val replies = Ipv4Packet.parse(packet)?.let { local.handle(it) }
                        if (replies == null) session.writePacket(packet) else replies.forEach { tun.writePacket(it) }
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

    private suspend fun readLocalDns(): LocalDnsConfig {
        if (dnsTransport == null) return LocalDnsConfig.NONE
        val config = try {
            localDnsSource.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogLevel.WARN, TAG, "No se pudo leer el DNS de la red: ${e.message}")
            LocalDnsConfig.NONE
        }
        if (config.servers.isNotEmpty()) {
            logger.log(
                LogLevel.DEBUG,
                TAG,
                "DNS de la red: ${config.servers.joinToString()}" +
                    if (config.searchDomains.isEmpty()) "" else "; dominios ${config.searchDomains.joinToString()}",
            )
        }
        return config
    }

    /** El DNS de la red local para esta sesión, o null si no hay nada que atender con él. */
    private fun localDnsFor(config: LocalDnsConfig, settings: AppSettings, rules: RuleSet?, reserved: List<Ipv4Route>): LocalDns? {
        val transport = dnsTransport ?: return null
        val resolver = LocalDnsResolver(config.servers, transport, reserved, logger)
        if (resolver.servers.isEmpty()) {
            if (config.servers.isNotEmpty()) logger.log(LogLevel.DEBUG, TAG, "El DNS de la red no es interno: la red local va como siempre")
            return null
        }
        val policy = LocalDnsPolicy(config.searchDomains, settings.intranetDirect, rules)
        if (settings.intranetDirect && config.searchDomains.isNotEmpty()) {
            logger.log(LogLevel.INFO, TAG, "Red local sin proxy: ${config.searchDomains.joinToString()}")
        }
        return LocalDns(policy, resolver)
    }

    private companion object {
        const val TAG = "Tunnel"
        const val REQUEUE_CAPACITY = 256
        const val MTU = 1500

        // Cuántos nombres que salen por el proxy en vez de por Otro País se recuerdan para mostrarlos.
        const val MAX_VIA_PROXY = 20

        // Si el servidor VPN no empuja un DNS: una dirección pública, que de todos modos va por la VPN.
        const val FALLBACK_DNS = "1.1.1.1"

        val VPN_RETRY_FIRST = 5.seconds
        val VPN_RETRY_MAX = 60.seconds
    }
}
