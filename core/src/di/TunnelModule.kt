package org.uci.proxy.core.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.Socks5Connector
import org.uci.proxy.core.settings.NameServiceReporter
import org.uci.proxy.core.settings.NameServiceStatusRepository
import org.uci.proxy.core.vpn.OpenVpnClient
import org.uci.proxy.core.vpn.OpenVpnExit
import org.uci.proxy.core.vpn.VpnExit
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Lo que necesita un proceso que ejecuta el túnel. Solo lo cargan esos procesos: hoy la app de
 * Android; más adelante la extensión de iOS y el proceso elevado de escritorio.
 * Requiere [coreModule] y que la plataforma enlace `LocalAddresses` y `HostResolver`.
 */
val tunnelModule = module {
    singleOf(::TunnelStateStore) { bind<TunnelMonitor>() }
    singleOf(::TunAddressPlanner)
    // Reloj monótono: los temporizadores TCP no deben verse afectados por cambios de hora del sistema.
    single<TimeSource> { TimeSource.Monotonic }
    // Quien se entera de qué servicio de nombres funcionó: por defecto, el repositorio de este mismo proceso. El motor de
    // escritorio, que corre aparte, lo sustituye por uno que avisa a la ventana.
    single<NameServiceReporter> { get<NameServiceStatusRepository>() }
    single {
        TunnelEngineFactory(
            connectors = listOf(get<HttpConnectConnector>(), get<Socks5Connector>()),
            randomBytes = get(),
            timeSource = get(),
            logger = get(),
            nameReporter = get(),
            // Solo Android lo enlaza; sin él las reglas de app no coinciden.
            owner = getOrNull<ConnectionOwner>() ?: ConnectionOwner.Unknown,
        )
    }
    single<VpnExit> {
        OpenVpnExit(
            client = OpenVpnClient(
                crypto = get(),
                tls = get(),
                random = get(),
                logger = get(),
                nowSeconds = { get<Clock>().now().epochSeconds },
            ),
            connectors = listOf(get<HttpConnectConnector>(), get<Socks5Connector>()),
            random = get(),
            logger = get(),
            reporter = get(),
        )
    }
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
            vpnExit = get(),
        )
    }
}
