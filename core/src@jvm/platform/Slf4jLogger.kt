package org.uci.proxy.core.platform

import org.slf4j.LoggerFactory
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger

class Slf4jLogger : Logger {
    override fun log(level: LogLevel, tag: String, message: String) {
        val logger = LoggerFactory.getLogger(tag)
        when (level) {
            LogLevel.DEBUG -> logger.debug(message)
            LogLevel.INFO -> logger.info(message)
            LogLevel.WARN -> logger.warn(message)
            LogLevel.ERROR -> logger.error(message)
        }
    }
}
