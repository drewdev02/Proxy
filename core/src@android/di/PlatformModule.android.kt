package org.uci.proxy.core.di

import android.content.Context
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.uci.proxy.core.log.BufferedLogger
import org.uci.proxy.core.platform.ActivityPrompt
import org.uci.proxy.core.platform.AndroidKeystoreProtector
import org.uci.proxy.core.platform.AndroidLocalAddresses
import org.uci.proxy.core.platform.AndroidLocalNetworkPermission
import org.uci.proxy.core.platform.AndroidVpnConsent
import org.uci.proxy.core.platform.AndroidPlatformInfo
import org.uci.proxy.core.platform.JavaHostResolver
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.platform.LogcatLogger
import org.uci.proxy.core.platform.SharedPreferencesKeyValueStore
import org.uci.proxy.core.port.HostResolver
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalAddresses
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.storage.EncryptedSecretStore
import org.uci.proxy.core.vpn.JcaOpenVpnCrypto
import org.uci.proxy.core.vpn.OpenVpnCrypto
import org.uci.proxy.core.vpn.SslEngineTlsSessionFactory
import org.uci.proxy.core.vpn.TlsSessionFactory

private const val SETTINGS_FILE = "proxyuci.settings"
private const val SECRETS_FILE = "proxyuci.secrets"
private const val SECRETS_KEY_ALIAS = "proxyuci.secrets"

/** El `Context` lo registra la app con `androidContext(...)` al arrancar Koin. */
actual val platformModule: Module = module {
    // Dispatchers.IO no existe en el código común, por eso lo aporta cada plataforma.
    single { AppDispatchers(background = Dispatchers.IO) }
    singleOf(::AndroidPlatformInfo) { bind<PlatformInfo>() }
    // El cliente OpenVPN propio (modo Otro País) usa la criptografía y el TLS estándar de Java.
    single<OpenVpnCrypto> { JcaOpenVpnCrypto() }
    single<TlsSessionFactory> { SslEngineTlsSessionFactory() }
    // Logcat sigue recibiéndolo todo; además se guarda para la pantalla «Registro».
    single<Logger> { BufferedLogger(LogcatLogger(), get(), get()) }
    singleOf(::JavaSecureRandomBytes) { bind<RandomBytes>() }
    // Cada permiso tiene su propio puente hacia la Activity, que es quien muestra el diálogo del sistema.
    single { AndroidLocalNetworkPermission(get<Context>(), ActivityPrompt()) } bind LocalNetworkPermission::class
    single { AndroidVpnConsent(get<Context>(), ActivityPrompt()) }
    singleOf(::AndroidLocalAddresses) { bind<LocalAddresses>() }
    single<HostResolver> { JavaHostResolver(get<AppDispatchers>().background) }
    single<KeyValueStore> { SharedPreferencesKeyValueStore(get<Context>(), SETTINGS_FILE) }
    single<SecretStore> {
        EncryptedSecretStore(
            storage = SharedPreferencesKeyValueStore(get<Context>(), SECRETS_FILE),
            protector = AndroidKeystoreProtector(SECRETS_KEY_ALIAS),
        )
    }
}
