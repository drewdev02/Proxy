package org.uci.proxy.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.uci.proxy.usage.UsageViewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import org.uci.proxy.quota.QuotaTracker
import org.uci.proxy.quota.HttpUciQuotaService
import org.uci.proxy.quota.QuotaMonitor
import org.uci.proxy.quota.QuotaNotifier
import org.uci.proxy.quota.QuotaStore
import org.uci.proxy.quota.UciQuotaService
import org.uci.proxy.connection.ConnectionViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.settings.SettingsViewModel
import org.uci.proxy.i18n.AppLanguageSetting
import org.uci.proxy.i18n.StoredLanguageSetting
import kotlin.time.TimeSource

val uiModule = module {
    // El idioma lo guarda la app; Android 13 o superior lo sustituye por el del sistema.
    single { StoredLanguageSetting(get()) }
    single<AppLanguageSetting> { get<StoredLanguageSetting>() }
    // La cuota de la UCI: se consulta en este proceso. Android sustituye el aviso por una notificación.
    single { QuotaStore(get()) }
    single<UciQuotaService> { HttpUciQuotaService() }
    single<QuotaNotifier> { QuotaNotifier.None }
    single {
        QuotaMonitor(
            profiles = get(),
            activeProfile = get(),
            tunnel = get(),
            control = get(),
            settings = get(),
            service = get(),
            store = get(),
            notifier = get(),
            clock = get(),
            dispatchers = get(),
            logger = get(),
        )
    } bind QuotaTracker::class
    viewModelOf(::ProfileListViewModel)
    viewModel {
        ProfileEditorViewModel(
            repository = get(),
            validator = get(),
            idGenerator = get(),
            proxyTester = get(),
            localNetworkPermission = get(),
            logger = get(),
            catalog = get(),
            settings = get(),
        )
    }
    viewModelOf(::LogsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { UsageViewModel(get(), get(), get(), get()) }
    // El reloj va a mano: iOS no carga el módulo del túnel, que es donde está el TimeSource.
    viewModel { ConnectionViewModel(get(), get(), get(), get(), get(), get(), get(), TimeSource.Monotonic, get(), get()) }
}
