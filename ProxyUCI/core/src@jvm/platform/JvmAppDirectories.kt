package org.uci.proxy.core.platform

import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import java.nio.file.Path
import kotlin.io.path.Path

/** Carpeta de datos de la app según la convención de cada sistema de escritorio. */
class JvmAppDirectories(
    platformInfo: PlatformInfo,
    userHome: String,
    environment: (String) -> String?,
) {
    val dataDir: Path = when (platformInfo.kind) {
        PlatformKind.WINDOWS ->
            (environment("LOCALAPPDATA")?.let(::Path) ?: Path(userHome, "AppData", "Local")).resolve(APP_FOLDER)
        PlatformKind.MACOS -> Path(userHome, "Library", "Application Support", APP_FOLDER)
        else -> (environment("XDG_CONFIG_HOME")?.let(::Path) ?: Path(userHome, ".config")).resolve(APP_FOLDER)
    }

    companion object {
        const val APP_FOLDER = "ProxyUCI"

        fun fromSystem(platformInfo: PlatformInfo) = JvmAppDirectories(
            platformInfo = platformInfo,
            userHome = System.getProperty("user.home"),
            environment = System::getenv,
        )
    }
}
