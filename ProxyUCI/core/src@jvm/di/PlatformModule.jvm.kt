package org.uci.proxy.core.di

import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.platform.FileKeyValueStore
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.platform.JvmAppDirectories
import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.core.platform.MacKeychainSecretStore
import org.uci.proxy.core.platform.Slf4jLogger
import org.uci.proxy.core.platform.WindowsDpapiProtector
import org.uci.proxy.core.port.GrantedLocalNetworkPermission
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.storage.EncryptedSecretStore

private const val KEYCHAIN_SERVICE = "org.uci.proxy"

/**
 * Windows y macOS comparten este target, así que la implementación se elige aquí, en ejecución,
 * según [PlatformInfo]. Es el único sitio del proyecto que ramifica por sistema de escritorio.
 */
actual val platformModule: Module = module {
    // Dispatchers.IO no existe en el código común, por eso lo aporta cada plataforma.
    single { AppDispatchers(background = Dispatchers.IO) }
    single<PlatformInfo> { JvmPlatformInfo.fromSystemProperties() }
    singleOf(::Slf4jLogger) { bind<Logger>() }
    singleOf(::JavaSecureRandomBytes) { bind<RandomBytes>() }
    singleOf(::GrantedLocalNetworkPermission) { bind<LocalNetworkPermission>() }
    single { JvmAppDirectories.fromSystem(get()) }
    single<KeyValueStore> { FileKeyValueStore(get<JvmAppDirectories>().dataDir.resolve("settings.properties")) }
    single<SecretStore> {
        when (get<PlatformInfo>().kind) {
            PlatformKind.MACOS -> MacKeychainSecretStore(KEYCHAIN_SERVICE)
            PlatformKind.WINDOWS -> EncryptedSecretStore(
                storage = FileKeyValueStore(get<JvmAppDirectories>().dataDir.resolve("secrets.properties")),
                protector = WindowsDpapiProtector(),
            )
            else -> error("ProxyUCI no tiene almacén seguro para ${get<PlatformInfo>().kind.displayName}")
        }
    }
}
