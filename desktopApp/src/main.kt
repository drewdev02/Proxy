package org.uci.proxy

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.uci.proxy.core.desktop.EngineArguments
import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.di.initKoin
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    EngineArguments.value(args.toList(), EngineArguments.SELF_TEST)?.let { reportFile ->
        runSelfTest(reportFile)
        exitProcess(0)
    }

    // El mismo programa hace de motor cuando la ventana lo relanza con privilegios.
    if (EngineArguments.ENGINE in args) {
        runEngine(args.toList())
        exitProcess(0)
    }

    initKoin {
        modules(desktopAppModule(JvmPlatformInfo.fromSystemProperties()))
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "ProxyUCI") {
            App()
        }
    }
}
