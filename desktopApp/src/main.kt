package org.uci.proxy

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.uci.proxy.core.desktop.EngineArguments
import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.di.initKoin
import java.nio.file.Files
import kotlin.io.path.Path
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

    val readyFile = EngineArguments.value(args.toList(), EngineArguments.READY_FILE)
    if (readyFile != null) reportUncaughtErrorsTo("$readyFile.error")

    initKoin {
        modules(desktopAppModule(JvmPlatformInfo.fromSystemProperties()))
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "ProxyUCI") {
            App()
            // Solo para pruebas: avisa de que la ventana ya se compuso y sale. Un ejecutor de CI no tiene un
            // escritorio interactivo del que leer el título de la ventana, así que lo dice la propia app.
            if (readyFile != null) {
                LaunchedEffect(Unit) {
                    Files.writeString(Path(readyFile), "listo")
                    exitApplication()
                }
            }
        }
    }
}

/** Sin consola, un error en el hilo de la interfaz no deja rastro: se escribe en [path] para poder diagnosticarlo. */
private fun reportUncaughtErrorsTo(path: String) {
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        Files.writeString(
            Path(path),
            "Error no capturado en ${thread.name}\n${error.stackTraceToString()}\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
}
