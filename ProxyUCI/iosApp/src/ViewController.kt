package org.uci.proxy

import androidx.compose.ui.window.ComposeUIViewController
import org.uci.proxy.di.initKoin

fun ViewController() = ComposeUIViewController { App() }

/** Raíz de composición de iOS: la llama `iosApp.swift` una vez al arrancar. */
fun startKoinIos() {
    initKoin()
}
