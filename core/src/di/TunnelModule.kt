package org.uci.proxy.core.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.net.KtorDnsTransport
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.DnsTransport
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.port.EngineStateDirectories
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.OpenVpnClient
import org.uci.proxy.core.vpn.OpenVpnEngine
import org.uci.proxy.core.vpn.tailscale.TailscaleEngine
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Lo que necesita un proceso que ejecuta el túnel. Solo lo cargan esos procesos: hoy la app de
 * Android; más adelante la extensión de iOS y el proceso elevado de escritorio.
 * Requiere [coreModule] (de él salen los conectores, el `ProxyDialer` y el `TailscaleEngine`) y que la plataforma
 * enlace `LocalAddresses` y `HostResolver` (y, si sabe leerlo, `LocalDnsSource`).
 */
val tunnelModule = module {
    singleOf(::TunnelStateStore) { bind<TunnelMonitor>() }
    singleOf(::TunAddressPlanner)
    // Reloj monótono: los temporizadores TCP no deben verse afectados por cambios de hora del sistema.
    single<TimeSource> { TimeSource.Monotonic }
    // Las consultas al DNS de la red local, para abrir sin proxy lo interno.
    single<DnsTransport> { KtorDnsTransport(get(), get()) }
    single {
        TunnelEngineFactory(
            connectors = get(ProxyConnectors),
            randomBytes = get(),
            timeSource = get(),
            logger = get(),
            nameReporter = get(),
            // Solo Android lo enlaza; sin él las reglas de app no coinciden.
            owner = getOrNull<ConnectionOwner>() ?: ConnectionOwner.Unknown,
        )
    }
    single {
        OpenVpnEngine(
            client = OpenVpnClient(
                crypto = get(),
                tls = get(),
                random = get(),
                logger = get(),
                nowSeconds = { get<Clock>().now().epochSeconds },
            ),
            dialer = get(),
            random = get(),
            logger = get(),
            // iOS no tiene cliente OpenVPN propio.
            isAvailable = openVpnAvailable(),
        )
    }
    // Los motores de esta plataforma; cada uno dice si puede usarse aquí (Tailscale, si su librería está).
    single { CountryEngines(listOf(get<OpenVpnEngine>(), get<TailscaleEngine>())) }
    // Con get() explícitos: las rutas públicas usan el valor por defecto del constructor.
    single {
        TunnelRunner(
            profiles = get(),
            addressPlanner = get(),
            engineFactory = get(),
            hostResolver = get(),
            stateStore = get(),
            dispatchers = get(),
            logger = get(),
            engines = get(),
            stateDirectories = getOrNull<EngineStateDirectories>() ?: EngineStateDirectories.None,
            localDnsSource = getOrNull<LocalDnsSource>() ?: LocalDnsSource.None,
            dnsTransport = get(),
            traffic = get(),
            timeSource = get(),
        )
    }
}
