package org.uci.proxy.core.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.core.platform.Slf4jLogger
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo

actual val platformModule: Module = module {
    single<PlatformInfo> { JvmPlatformInfo.fromSystemProperties() }
    singleOf(::Slf4jLogger) { bind<Logger>() }
}
