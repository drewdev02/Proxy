package org.uci.proxy.database.di

import org.koin.core.module.Module
import org.koin.dsl.module
import org.uci.proxy.core.log.LogRecorder
import org.uci.proxy.core.port.LogHistory
import org.uci.proxy.database.ProxyDatabase
import org.uci.proxy.database.log.RoomLogHistory

/** Dónde vive el archivo de la base en cada plataforma; enlaza [ProxyDatabase]. */
expect val platformDatabaseModule: Module

/** La base de datos y lo que la usa. Solo para el proceso de la interfaz (ver [LogRecorder]). */
val databaseModule = module {
    includes(platformDatabaseModule)
    single<LogHistory> { RoomLogHistory(get<ProxyDatabase>().logDao()) }
    single { LogRecorder(get(), get(), get(), get()) }
}
