package org.uci.proxy.core.log

import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import kotlin.time.Clock

/**
 * Escribe cada mensaje donde ya iba (el registro de la plataforma, [delegate]) y además lo guarda en [buffer] para
 * la pantalla de registro.
 */
class BufferedLogger(
    private val delegate: Logger,
    private val buffer: LogBuffer,
    private val clock: Clock,
) : Logger {
    override fun log(level: LogLevel, tag: String, message: String) {
        delegate.log(level, tag, message)
        buffer.append(clock.now().toEpochMilliseconds(), level, tag, message)
    }
}
