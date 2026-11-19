package org.uci.proxy.database.di

import org.koin.core.module.Module
import org.koin.dsl.module
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.database.ProxyDatabase
import org.uci.proxy.database.buildProxyDatabase
import org.uci.proxy.database.proxyDatabaseBuilder

actual val platformDatabaseModule: Module = module {
    single<ProxyDatabase> { proxyDatabaseBuilder().buildProxyDatabase(get<AppDispatchers>().background) }
}
