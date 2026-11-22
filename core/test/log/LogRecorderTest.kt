package org.uci.proxy.core.log

import org.uci.proxy.core.model.LogEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.common.AppDispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LogRecorderTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val now = Instant.fromEpochMilliseconds(10.days.inWholeMilliseconds)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val history = InMemoryLogHistory()
    private val buffer = LogBuffer(capacity = 5)
    private val recorder = LogRecorder(buffer, history, clock, AppDispatchers(dispatcher), saveInterval = 2.seconds, maxEntries = 4)

    @AfterTest
    fun stop() = recorder.stop()

    private fun log(message: String, at: Long = now.toEpochMilliseconds()) = buffer.append(at, LogLevel.INFO, "T", message)

    @Test
    fun `new lines are saved in batches`() = scope.runTest {
        recorder.start()
        runCurrent()
        log("uno")
        log("dos")
        assertEquals(emptyList(), history.all())

        advanceTimeBy(2.seconds + 1.seconds / 10)

        assertEquals(listOf("uno", "dos"), history.all().map { it.message })
        // El bucle de guardado no acaba solo: si sigue vivo, runTest avanza el reloj virtual para siempre.
        recorder.stop()
    }

    @Test
    fun `a line is saved only once`() = scope.runTest {
        log("uno")
        recorder.saveNew()
        log("dos")
        recorder.saveNew()
        recorder.saveNew()

        assertEquals(listOf("uno", "dos"), history.all().map { it.message })
    }

    @Test
    fun `on start the previous session comes back without being saved again`() = scope.runTest {
        history.append(listOf(LogEntry(7, now.toEpochMilliseconds() - 1_000, LogLevel.WARN, "T", "de ayer")))
        log("de hoy")

        recorder.start()
        runCurrent()
        recorder.stop()
        recorder.saveNew()

        assertEquals(listOf("de ayer", "de hoy"), buffer.entries.value.map { it.message })
        assertEquals(listOf("de ayer", "de hoy"), history.all().map { it.message })
    }

    @Test
    fun `on start old lines and the excess are dropped`() = scope.runTest {
        val old = now.toEpochMilliseconds() - 8.days.inWholeMilliseconds
        history.append(listOf(LogEntry(0, old, LogLevel.INFO, "T", "viejo")))
        history.append((1..5).map { LogEntry(it.toLong(), now.toEpochMilliseconds(), LogLevel.INFO, "T", "$it") })

        recorder.start()
        runCurrent()
        recorder.stop()

        assertEquals(listOf("2", "3", "4", "5"), history.all().map { it.message })
    }

    @Test
    fun `clearing empties the screen at once and the saved log`() = scope.runTest {
        log("uno")
        recorder.saveNew()

        recorder.clear()
        assertEquals(emptyList(), buffer.entries.value)
        runCurrent()

        assertEquals(emptyList(), history.all())
    }

    @Test
    fun `after clearing new lines are still saved`() = scope.runTest {
        log("uno")
        recorder.saveNew()
        recorder.clear()
        runCurrent()

        log("dos", at = now.toEpochMilliseconds() + 1)
        recorder.saveNew()

        assertEquals(listOf("dos"), history.all().map { it.message })
    }
}
