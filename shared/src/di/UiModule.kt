package org.uci.proxy.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.uci.proxy.usage.UsageViewModel
import org.koin.dsl.module
import org.uci.proxy.connection.ConnectionViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.settings.SettingsViewModel
import kotlin.time.TimeSource

val uiModule = module {
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
    viewModelOf(::UsageViewModel)
    // El reloj va a mano: iOS no carga el módulo del túnel, que es donde está el TimeSource.
    viewModel { ConnectionViewModel(get(), get(), get(), get(), get(), get(), get(), TimeSource.Monotonic, get()) }
}
