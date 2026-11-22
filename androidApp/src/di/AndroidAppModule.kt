package org.uci.proxy.di

import org.koin.dsl.module
import org.uci.proxy.common.SystemSettings
import org.uci.proxy.settings.AndroidSystemSettings
import org.uci.proxy.i18n.AndroidLanguageSetting
import org.uci.proxy.i18n.AppLanguageSetting
import org.uci.proxy.quota.AndroidQuotaNotifier
import org.uci.proxy.quota.QuotaNotifier
import org.uci.proxy.core.platform.AndroidTunnelController
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.vpn.ProxyVpnService

/** Lo que solo puede enlazarse aquí porque nombra clases de la app, como el servicio de VPN. */
val androidAppModule = module {
    single<TunnelControl> {
        AndroidTunnelController(
            context = get(),
            serviceClass = ProxyVpnService::class.java,
            localNetworkPermission = get(),
            vpnConsent = get(),
            stateStore = get(),
        )
    }
    single<QuotaNotifier> { AndroidQuotaNotifier(get()) }
    single<AppLanguageSetting> { AndroidLanguageSetting(get(), get()) }
    single<SystemSettings> { AndroidSystemSettings(get()) }
}
