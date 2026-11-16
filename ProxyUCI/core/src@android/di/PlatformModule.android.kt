package org.uci.proxy.core.di

import android.content.Context
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.platform.AndroidKeystoreProtector
import org.uci.proxy.core.platform.AndroidPlatformInfo
import org.uci.proxy.core.platform.LogcatLogger
import org.uci.proxy.core.platform.SharedPreferencesKeyValueStore
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.storage.EncryptedSecretStore

private const val SETTINGS_FILE = "proxyuci.settings"
private const val SECRETS_FILE = "proxyuci.secrets"
private const val SECRETS_KEY_ALIAS = "proxyuci.secrets"

/** El `Context` lo registra la app con `androidContext(...)` al arrancar Koin. */
actual val platformModule: Module = module {
    singleOf(::AndroidPlatformInfo) { bind<PlatformInfo>() }
    singleOf(::LogcatLogger) { bind<Logger>() }
    single<KeyValueStore> { SharedPreferencesKeyValueStore(get<Context>(), SETTINGS_FILE) }
    single<SecretStore> {
        EncryptedSecretStore(
            storage = SharedPreferencesKeyValueStore(get<Context>(), SECRETS_FILE),
            protector = AndroidKeystoreProtector(SECRETS_KEY_ALIAS),
        )
    }
}
