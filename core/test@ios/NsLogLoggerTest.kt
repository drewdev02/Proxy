package org.uci.proxy.core

import org.uci.proxy.core.platform.NsLogLogger
import org.uci.proxy.core.port.LogLevel
import kotlin.test.Test

class NsLogLoggerTest {
    private val logger = NsLogLogger()

    @Test
    fun `logs a plain message without crashing`() {
        logger.log(LogLevel.INFO, "ProxyUCI", "conexión establecida")
    }

    @Test
    fun `percent signs in the message are not treated as format specifiers`() {
        logger.log(LogLevel.WARN, "ProxyUCI", "progreso 100% %@ %s %d")
    }
}
