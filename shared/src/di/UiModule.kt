package org.uci.proxy.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.uci.proxy.connection.ConnectionViewModel
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.settings.SettingsViewModel
import org.uci.proxy.tunnel.TunnelViewModel
import kotlin.time.TimeSource

val uiModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::ProfileListViewModel)
    viewModelOf(::ProfileEditorViewModel)
    viewModelOf(::TunnelViewModel)
    viewModelOf(::LogsViewModel)
    viewModelOf(::SettingsViewModel)
    // El reloj va a mano: iOS no carga el módulo del túnel, que es donde está el TimeSource.
    viewModel { ConnectionViewModel(get(), get(), get(), get(), get(), get(), get(), TimeSource.Monotonic) }
}
