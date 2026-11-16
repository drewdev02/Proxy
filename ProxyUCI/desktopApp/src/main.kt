package org.uci.proxy

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.uci.proxy.di.initKoin

fun main() {
    initKoin()
    application {
        Window(onCloseRequest = ::exitApplication, title = "ProxyUCI") {
            App()
        }
    }
}
