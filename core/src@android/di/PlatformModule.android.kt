package org.uci.proxy.core.di

import android.content.Context
import org.uci.proxy.core.port.EngineStateDirectories
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
import org.uci.proxy.core.platform.AndroidLocalDnsSource
import org.uci.proxy.core.platform.JavaHostResolver
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.platform.LogcatLogger
import org.uci.proxy.core.platform.SharedPreferencesKeyValueStore
import org.uci.proxy.core.platform.AndroidConnectionOwner
import org.uci.proxy.core.port.ConnectionOwner
import org.uci.proxy.core.port.HostResolver
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalAddresses
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.storage.EncryptedSecretStore
import org.uci.proxy.core.vpn.JcaOpenVpnCrypto
import org.uci.proxy.core.vpn.OpenVpnCrypto
import org.uci.proxy.core.vpn.SslEngineTlsSessionFactory
import org.uci.proxy.core.vpn.TlsSessionFactory
import org.uci.proxy.core.vpn.tailscale.NativeTailscaleBridge
import org.uci.proxy.core.vpn.tailscale.TailscaleBridge

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
    // El motor Tailscale de Otro País: su librería nativa (jniLibs de la app), si esta compilación la trae.
    single<TailscaleBridge> { NativeTailscaleBridge.fromSystem("tsbridge", onLoadError = { get<Logger>().log(LogLevel.WARN, "Tailscale", it) }) }
    // Logcat sigue recibiéndolo todo; además se guarda para la pantalla «Registro».
    single<Logger> { BufferedLogger(LogcatLogger(), get(), get()) }
    singleOf(::JavaSecureRandomBytes) { bind<RandomBytes>() }
    // Cada permiso tiene su propio puente hacia la Activity, que es quien muestra el diálogo del sistema.
    single { AndroidLocalNetworkPermission(get<Context>(), ActivityPrompt()) } bind LocalNetworkPermission::class
    single { AndroidVpnConsent(get<Context>(), ActivityPrompt()) }
    singleOf(::AndroidLocalAddresses) { bind<LocalAddresses>() }
    // Para las reglas de salida por app: qué app abrió cada conexión.
    single<ConnectionOwner> { AndroidConnectionOwner(get<Context>()) }
    // El estado de los motores de Otro País (la identidad de un nodo de Tailscale), un directorio privado por perfil.
    single<EngineStateDirectories> {
        val base = java.io.File(get<Context>().filesDir, "engines")
        EngineStateDirectories { id -> java.io.File(base, id).path }
    }
    single<HostResolver> { JavaHostResolver(get<AppDispatchers>().background) }
    // El DNS de la red real, para abrir sin proxy lo interno.
    single<LocalDnsSource> { AndroidLocalDnsSource(get<Context>()) }
    single<KeyValueStore> { SharedPreferencesKeyValueStore(get<Context>(), SETTINGS_FILE) }
    single<SecretStore> {
        EncryptedSecretStore(
            storage = SharedPreferencesKeyValueStore(get<Context>(), SECRETS_FILE),
            protector = AndroidKeystoreProtector(SECRETS_KEY_ALIAS),
        )
    }
}
