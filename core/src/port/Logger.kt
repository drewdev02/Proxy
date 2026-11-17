package org.uci.proxy.core.port

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

interface Logger {
    fun log(level: LogLevel, tag: String, message: String)
}
