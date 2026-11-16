package org.uci.proxy.core.di

import android.content.Context
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.uci.proxy.core.platform.ActivityPrompt
import org.uci.proxy.core.platform.AndroidKeystoreProtector
import org.uci.proxy.core.platform.AndroidLocalAddresses
import org.uci.proxy.core.platform.AndroidLocalNetworkPermission
import org.uci.proxy.core.platform.AndroidVpnConsent
import org.uci.proxy.core.platform.AndroidPlatformInfo
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.platform.LogcatLogger
import org.uci.proxy.core.platform.SharedPreferencesKeyValueStore
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalAddresses
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.storage.EncryptedSecretStore

private const val SETTINGS_FILE = "proxyuci.settings"
private const val SECRETS_FILE = "proxyuci.secrets"
private const val SECRETS_KEY_ALIAS = "proxyuci.secrets"

/** El `Context` lo registra la app con `androidContext(...)` al arrancar Koin. */
actual val platformModule: Module = module {
    // Dispatchers.IO no existe en el código común, por eso lo aporta cada plataforma.
    single { AppDispatchers(background = Dispatchers.IO) }
    singleOf(::AndroidPlatformInfo) { bind<PlatformInfo>() }
    singleOf(::LogcatLogger) { bind<Logger>() }
    singleOf(::JavaSecureRandomBytes) { bind<RandomBytes>() }
    // Cada permiso tiene su propio puente hacia la Activity, que es quien muestra el diálogo del sistema.
    single { AndroidLocalNetworkPermission(get<Context>(), ActivityPrompt()) } bind LocalNetworkPermission::class
    single { AndroidVpnConsent(get<Context>(), ActivityPrompt()) }
    singleOf(::AndroidLocalAddresses) { bind<LocalAddresses>() }
    single<KeyValueStore> { SharedPreferencesKeyValueStore(get<Context>(), SETTINGS_FILE) }
    single<SecretStore> {
        EncryptedSecretStore(
            storage = SharedPreferencesKeyValueStore(get<Context>(), SECRETS_FILE),
            protector = AndroidKeystoreProtector(SECRETS_KEY_ALIAS),
        )
    }
}
