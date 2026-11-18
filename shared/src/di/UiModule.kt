package org.uci.proxy.di

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
    viewModelOf(::ProfileEditorViewModel)
    viewModelOf(::TunnelViewModel)
    viewModelOf(::LogsViewModel)
    viewModelOf(::SettingsViewModel)
}
