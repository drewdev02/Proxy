package org.uci.proxy.core.platform

import android.os.Build
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind

class AndroidPlatformInfo : PlatformInfo {
    override val kind: PlatformKind = PlatformKind.ANDROID

    // RELEASE es null en las pruebas unitarias locales, donde android.jar solo trae stubs.
    override val osVersion: String = Build.VERSION.RELEASE ?: UNKNOWN_VERSION

    private companion object {
        const val UNKNOWN_VERSION = "desconocida"
    }
}
