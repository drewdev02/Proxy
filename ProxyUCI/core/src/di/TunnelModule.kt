package org.uci.proxy.core.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.Socks5Connector
import kotlin.time.TimeSource

/**
 * Lo que necesita un proceso que ejecuta el túnel. Solo lo cargan esos procesos: hoy la app de
 * Android; más adelante la extensión de iOS y el proceso elevado de escritorio.
 * Requiere [coreModule] y que la plataforma enlace `LocalAddresses`.
 */
val tunnelModule = module {
    singleOf(::TunnelStateStore) { bind<TunnelMonitor>() }
    singleOf(::TunAddressPlanner)
    // Reloj monótono: los temporizadores TCP no deben verse afectados por cambios de hora del sistema.
    single<TimeSource> { TimeSource.Monotonic }
    single {
        TunnelEngineFactory(
            connectors = listOf(get<HttpConnectConnector>(), get<Socks5Connector>()),
            randomBytes = get(),
            timeSource = get(),
            logger = get(),
        )
    }
    singleOf(::TunnelRunner)
}
