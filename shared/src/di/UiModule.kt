package org.uci.proxy.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.settings.SettingsViewModel
import org.uci.proxy.tunnel.TunnelViewModel

val uiModule = module {
    viewModelOf(::HomeViewModel)
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
    viewModelOf(::TunnelViewModel)
    viewModelOf(::LogsViewModel)
    viewModelOf(::SettingsViewModel)
}
