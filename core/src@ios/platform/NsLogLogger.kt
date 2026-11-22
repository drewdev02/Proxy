package org.uci.proxy.core.platform

import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import platform.Foundation.NSLog

class NsLogLogger : Logger {
    override fun log(level: LogLevel, tag: String, message: String) {
        // El texto va como formato con los '%' escapados. Pasarlo como argumento de "%@" revienta:
        // Kotlin/Native entrega los String de un vararg como cadena C, no como NSString.
        NSLog("[${level.name}] $tag: $message".replace("%", "%%"))
    }
}
