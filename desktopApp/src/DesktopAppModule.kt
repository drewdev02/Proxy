package org.uci.proxy

import org.koin.dsl.binds
import org.koin.dsl.module
import org.uci.proxy.core.desktop.DesktopTunnelController
import org.uci.proxy.core.desktop.EngineLauncher
import org.uci.proxy.core.desktop.MacElevatedEngineLauncher
import org.uci.proxy.core.desktop.WindowsElevatedEngineLauncher
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor

/** Clase principal del programa: el motor es este mismo programa relanzado con privilegios. */
internal const val MAIN_CLASS = "org.uci.proxy.MainKt"

/**
 * Lo que solo puede enlazarse aquí porque depende de cómo se lanza la app de escritorio.
 * En macOS y Windows sustituye el túnel "no disponible" por el que arranca el motor con privilegios.
 */
fun desktopAppModule(platformInfo: PlatformInfo) = module {
    val launcher: EngineLauncher? = when (platformInfo.kind) {
        PlatformKind.MACOS -> MacElevatedEngineLauncher.forCurrentProcess(MAIN_CLASS)
        PlatformKind.WINDOWS -> WindowsElevatedEngineLauncher.forCurrentProcess(MAIN_CLASS)
        else -> null
    }
    if (launcher != null) {
        single {
            DesktopTunnelController(
                profiles = get(),
                launcher = launcher,
                randomBytes = get(),
                dispatchers = get(),
                logger = get(),
                logBuffer = get(),
                settings = get(),
            )
        } binds arrayOf(TunnelControl::class, TunnelMonitor::class)
    }
}
