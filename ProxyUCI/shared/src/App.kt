package org.uci.proxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.home.HomeScreen
import org.uci.proxy.home.HomeViewModel

/** Requiere que la app haya llamado antes a [org.uci.proxy.di.initKoin]. */
@Composable
@DevelopmentEntryPoint
fun App() {
    MaterialTheme {
        val viewModel = koinViewModel<HomeViewModel>()
        HomeScreen(viewModel.uiState)
    }
}
