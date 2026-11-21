package org.uci.proxy.core.di

import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.binds
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.log.BufferedLogger
import org.uci.proxy.core.engine.UnavailableTunnel
import org.uci.proxy.core.platform.FileKeyValueStore
import org.uci.proxy.core.platform.JavaHostResolver
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.platform.JvmLocalAddresses
import org.uci.proxy.core.platform.JvmAppDirectories
import org.uci.proxy.core.port.EngineStateDirectories
import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.platform.macos.MacLocalDnsSource
import org.uci.proxy.core.platform.windows.WinLocalDnsSource
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.platform.MacKeychainSecretStore
import org.uci.proxy.core.platform.Slf4jLogger
import org.uci.proxy.core.platform.WindowsDpapiProtector
import org.uci.proxy.core.port.GrantedLocalNetworkPermission
import org.uci.proxy.core.port.HostResolver
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalAddresses
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.storage.EncryptedSecretStore
import org.uci.proxy.core.vpn.JcaOpenVpnCrypto
import org.uci.proxy.core.vpn.OpenVpnCrypto
import org.uci.proxy.core.vpn.SslEngineTlsSessionFactory
import org.uci.proxy.core.vpn.TlsSessionFactory
import org.uci.proxy.core.vpn.tailscale.NativeTailscaleBridge
import org.uci.proxy.core.vpn.tailscale.TailscaleBridge

private const val KEYCHAIN_SERVICE = "org.uci.proxy"

/**
 * Windows y macOS comparten este target, así que la implementación se elige aquí, en ejecución,
 * según [PlatformInfo]. Es el único sitio del proyecto que ramifica por sistema de escritorio.
 */
actual val platformModule: Module = module {
    // Dispatchers.IO no existe en el código común, por eso lo aporta cada plataforma.
    single { AppDispatchers(background = Dispatchers.IO) }
    single<PlatformInfo> { JvmPlatformInfo.fromSystemProperties() }
    // Logback sigue recibiéndolo todo; además se guarda para la pantalla «Registro».
    single<Logger> { BufferedLogger(Slf4jLogger(), get(), get()) }
    singleOf(::JavaSecureRandomBytes) { bind<RandomBytes>() }
    // El cliente OpenVPN propio (modo Otro País) usa la criptografía y el TLS estándar de Java.
    single<OpenVpnCrypto> { JcaOpenVpnCrypto() }
    single<TlsSessionFactory> { SslEngineTlsSessionFactory() }
    // El motor Tailscale de Otro País: su librería nativa, si este sistema la trae.
    single<TailscaleBridge> {
        val (dir, file) = NativeTailscaleBridge.desktopResourceDir() ?: return@single TailscaleBridge.Unavailable
        NativeTailscaleBridge.fromResource(dir, file, onLoadError = { get<Logger>().log(LogLevel.WARN, "Tailscale", it) })
    }
    singleOf(::GrantedLocalNetworkPermission) { bind<LocalNetworkPermission>() }
    // Sin túnel por defecto. En macOS la app de escritorio lo sustituye en su propio módulo, que es
    // donde se sabe cómo relanzar el programa como motor; el de Windows llega en la capa 6.
    singleOf(::UnavailableTunnel) { binds(listOf(TunnelControl::class, TunnelMonitor::class)) }
    // Lo que necesita el proceso del motor además de tunnelModule.
    singleOf(::JvmLocalAddresses) { bind<LocalAddresses>() }
    single<HostResolver> { JavaHostResolver(get<AppDispatchers>().background) }
    // El DNS de la red real, para abrir sin proxy lo interno.
    single<LocalDnsSource> {
        when (get<PlatformInfo>().kind) {
            PlatformKind.MACOS -> MacLocalDnsSource(ProcessCommandRunner())
            PlatformKind.WINDOWS -> WinLocalDnsSource(ProcessCommandRunner())
            else -> LocalDnsSource.None
        }
    }
    single { JvmAppDirectories.fromSystem(get()) }
    // El estado de los motores de Otro País (la identidad de un nodo de Tailscale), un directorio por perfil.
    single<EngineStateDirectories> {
        val base = get<JvmAppDirectories>().dataDir.resolve("engines")
        EngineStateDirectories { id -> base.resolve(id).toString() }
    }
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
