package org.uci.proxy.core.platform

import android.util.Log
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger

class LogcatLogger : Logger {
    override fun log(level: LogLevel, tag: String, message: String) {
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, tag, message)
    }
}
