package org.uci.proxy.core.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.platform.IosPlatformInfo
import org.uci.proxy.core.platform.KeychainSecretStore
import org.uci.proxy.core.platform.NsLogLogger
import org.uci.proxy.core.platform.UserDefaultsKeyValueStore
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.SecretStore
import platform.Foundation.NSUserDefaults

private const val KEYCHAIN_SERVICE = "org.uci.proxy"

// Cuando exista la extensión de red (capa 7), ambos almacenes pasarán a un App Group y a un
// grupo de llavero compartidos para que app y extensión lean los mismos datos.
actual val platformModule: Module = module {
    singleOf(::IosPlatformInfo) { bind<PlatformInfo>() }
    singleOf(::NsLogLogger) { bind<Logger>() }
    single<KeyValueStore> { UserDefaultsKeyValueStore(NSUserDefaults.standardUserDefaults) }
    single<SecretStore> { KeychainSecretStore(KEYCHAIN_SERVICE) }
}
