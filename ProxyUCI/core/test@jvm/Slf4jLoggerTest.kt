package org.uci.proxy.core

import org.uci.proxy.core.platform.Slf4jLogger
import org.uci.proxy.core.port.LogLevel
import kotlin.test.Test

class Slf4jLoggerTest {
    @Test
    fun `logs at every level without crashing`() {
        val logger = Slf4jLogger()

        LogLevel.entries.forEach { logger.log(it, "ProxyUCI", "mensaje de prueba {} %s") }
    }
}
