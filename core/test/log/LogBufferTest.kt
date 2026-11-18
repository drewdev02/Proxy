package org.uci.proxy.core.log

import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.port.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LogBufferTest {
    @Test
    fun `entries keep their order and get consecutive ids`() {
        val buffer = LogBuffer()

        buffer.append(10, LogLevel.INFO, "A", "uno")
        buffer.append(20, LogLevel.WARN, "B", "dos")

        assertEquals(
            listOf(
                LogEntry(0, 10, LogLevel.INFO, "A", "uno"),
                LogEntry(1, 20, LogLevel.WARN, "B", "dos"),
            ),
            buffer.entries.value,
        )
    }

    @Test
    fun `when it is full the oldest entries are dropped`() {
        val buffer = LogBuffer(capacity = 3)

        repeat(5) { buffer.append(it.toLong(), LogLevel.DEBUG, "T", "mensaje $it") }

        assertEquals(listOf("mensaje 2", "mensaje 3", "mensaje 4"), buffer.entries.value.map { it.message })
        assertEquals(listOf(2L, 3L, 4L), buffer.entries.value.map { it.id })
    }

    @Test
    fun `an enormous message is cut so it cannot fill the memory`() {
        val buffer = LogBuffer()

        buffer.append(0, LogLevel.ERROR, "T", "x".repeat(LogBuffer.MAX_MESSAGE_LENGTH * 3))

        val message = buffer.entries.value.single().message
        assertEquals(LogBuffer.MAX_MESSAGE_LENGTH + 1, message.length)
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun `clearing empties the buffer and ids start again`() {
        val buffer = LogBuffer()
        buffer.append(0, LogLevel.INFO, "T", "antes")

        buffer.clear()
        buffer.append(1, LogLevel.INFO, "T", "después")

        assertEquals(listOf(LogEntry(0, 1, LogLevel.INFO, "T", "después")), buffer.entries.value)
    }

    @Test
    fun `a buffer needs a positive capacity`() {
        assertFailsWith<IllegalArgumentException> { LogBuffer(capacity = 0) }
    }

    @Test
    fun `the buffered logger writes to the platform logger and to the buffer with the current time`() {
        val platform = RecordingLogger()
        val buffer = LogBuffer()
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_123)
        }

        BufferedLogger(platform, buffer, clock).log(LogLevel.WARN, "Relay", "El proxy no abrió example.org:443")

        assertEquals(listOf(LogLevel.WARN to "El proxy no abrió example.org:443"), platform.entries)
        assertEquals(
            listOf(LogEntry(0, 1_700_000_000_123, LogLevel.WARN, "Relay", "El proxy no abrió example.org:443")),
            buffer.entries.value,
        )
    }
}
