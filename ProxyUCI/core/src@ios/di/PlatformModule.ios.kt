package org.uci.proxy.core.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.platform.IosPlatformInfo
import org.uci.proxy.core.platform.NsLogLogger
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo

actual val platformModule: Module = module {
    singleOf(::IosPlatformInfo) { bind<PlatformInfo>() }
    singleOf(::NsLogLogger) { bind<Logger>() }
}
