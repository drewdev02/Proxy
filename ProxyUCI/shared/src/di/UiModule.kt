package org.uci.proxy.di

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.uci.proxy.home.HomeViewModel

val uiModule = module {
    viewModelOf(::HomeViewModel)
}
