package org.uci.proxy.core.platform

import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind

/**
 * Windows y macOS comparten el target `jvm`, así que aquí la plataforma se detecta en ejecución.
 * Los valores llegan por constructor para poder probar la detección sin depender del equipo.
 */
class JvmPlatformInfo(
    osName: String,
    override val osVersion: String,
) : PlatformInfo {
    override val kind: PlatformKind = detectKind(osName)

    companion object {
        fun fromSystemProperties(): JvmPlatformInfo = JvmPlatformInfo(
            osName = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
        )

        private fun detectKind(osName: String): PlatformKind {
            val name = osName.lowercase()
            return when {
                // "darwin" contiene "win": por eso Windows se reconoce por prefijo y no por contenido.
                name.startsWith("windows") -> PlatformKind.WINDOWS
                name.startsWith("mac") || name.contains("darwin") -> PlatformKind.MACOS
                else -> PlatformKind.LINUX
            }
        }
    }
}
