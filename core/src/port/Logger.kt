package org.uci.proxy.core.port

import org.uci.proxy.core.model.LogLevel

interface Logger {
    fun log(level: LogLevel, tag: String, message: String)
}
