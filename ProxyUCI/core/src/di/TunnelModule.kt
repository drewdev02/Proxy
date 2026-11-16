package org.uci.proxy.core.di

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.engine.TunAddressPlanner
import org.uci.proxy.core.engine.TunnelEngineFactory
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.port.TunnelMonitor

/**
 * Lo que necesita un proceso que ejecuta el túnel. Solo lo cargan esos procesos: hoy la app de
 * Android; más adelante la extensión de iOS y el proceso elevado de escritorio.
 * Requiere que la plataforma enlace `LocalAddresses`.
 */
val tunnelModule = module {
    singleOf(::TunnelStateStore) { bind<TunnelMonitor>() }
    singleOf(::TunAddressPlanner)
    singleOf(::TunnelEngineFactory)
    singleOf(::TunnelRunner)
}
