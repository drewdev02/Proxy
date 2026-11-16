package org.uci.proxy.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import platform.Foundation.NSProcessInfo

/**
 * Usa NSProcessInfo y no UIDevice para que funcione también dentro de la extensión de red,
 * donde UIKit no está disponible.
 */
class IosPlatformInfo : PlatformInfo {
    override val kind: PlatformKind = PlatformKind.IOS

    @OptIn(ExperimentalForeignApi::class)
    override val osVersion: String = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        "$majorVersion.$minorVersion.$patchVersion"
    }
}
