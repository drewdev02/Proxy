package org.uci.proxy.core.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.binds
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.log.BufferedLogger
import org.uci.proxy.core.engine.UnavailableTunnel
import org.uci.proxy.core.platform.IosPlatformInfo
import org.uci.proxy.core.platform.KeychainSecretStore
import org.uci.proxy.core.platform.NsLogLogger
import org.uci.proxy.core.platform.SecRandomBytes
import org.uci.proxy.core.platform.UserDefaultsKeyValueStore
import org.uci.proxy.core.port.GrantedLocalNetworkPermission
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.vpn.OpenVpnCrypto
import org.uci.proxy.core.vpn.TlsSessionFactory
import org.uci.proxy.core.vpn.UnavailableOpenVpnCrypto
import org.uci.proxy.core.vpn.UnavailableTlsSessionFactory
import platform.Foundation.NSUserDefaults

private const val KEYCHAIN_SERVICE = "org.uci.proxy"

// Cuando exista la extensión de red (capa 7), ambos almacenes pasarán a un App Group y a un
// grupo de llavero compartidos para que app y extensión lean los mismos datos.
actual val platformModule: Module = module {
    // Dispatchers.IO no existe en el código común, por eso lo aporta cada plataforma.
    single { AppDispatchers(background = Dispatchers.IO) }
    singleOf(::IosPlatformInfo) { bind<PlatformInfo>() }
    // NSLog sigue recibiéndolo todo; además se guarda para la pantalla «Registro».
    single<Logger> { BufferedLogger(NsLogLogger(), get(), get()) }
    singleOf(::SecRandomBytes) { bind<RandomBytes>() }
    // iOS no tiene cliente VPN propio: el modo Otro País queda fuera.
    single<OpenVpnCrypto> { UnavailableOpenVpnCrypto }
    single<TlsSessionFactory> { UnavailableTlsSessionFactory }
    // iOS muestra por su cuenta el aviso de red local la primera vez que la app se conecta.
    singleOf(::GrantedLocalNetworkPermission) { bind<LocalNetworkPermission>() }
    // El túnel de iOS llega en la capa 7, con la extensión de red.
    singleOf(::UnavailableTunnel) { binds(listOf(TunnelControl::class, TunnelMonitor::class)) }
    single<KeyValueStore> { UserDefaultsKeyValueStore(NSUserDefaults.standardUserDefaults) }
    single<SecretStore> { KeychainSecretStore(KEYCHAIN_SERVICE) }
}
