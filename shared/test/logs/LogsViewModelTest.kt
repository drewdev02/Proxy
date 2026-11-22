package org.uci.proxy.logs

import kotlinx.datetime.TimeZone
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.uci.proxy.core.log.InMemoryLogHistory
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.log.LogRecorder
import org.uci.proxy.core.common.AppDispatchers
import kotlin.time.Clock
import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.fakes.MainDispatcherTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogsViewModelTest : MainDispatcherTest() {
    private val buffer = LogBuffer()
    private val recorder = LogRecorder(buffer, InMemoryLogHistory(), Clock.System, AppDispatchers(UnconfinedTestDispatcher()))

    // Se crea en el primer uso, ya dentro de la prueba: viewModelScope necesita el despachador principal que instala la
    // clase base, y el orden entre sus @BeforeTest y los de esta clase cambia según la plataforma (en iOS va al revés).
    private val viewModel by lazy { LogsViewModel(buffer, recorder).also { it.uiState.keepSubscribed() } }

    private fun log(level: LogLevel, message: String) = buffer.append(0, level, "Relay", message)

    private fun messages() = viewModel.uiState.value.entries.map { it.message }

    @Test
    fun `at first it shows every message in arrival order`() {
        log(LogLevel.DEBUG, "uno")
        log(LogLevel.ERROR, "dos")

        assertEquals(listOf("uno", "dos"), messages())
        assertEquals(LogLevel.DEBUG, viewModel.uiState.value.minLevel)
        assertFalse(viewModel.uiState.value.paused)
    }

    @Test
    fun `new messages show up on their own`() {
        log(LogLevel.INFO, "antes")
        assertEquals(listOf("antes"), messages())

        log(LogLevel.INFO, "después")

        assertEquals(listOf("antes", "después"), messages())
    }

    @Test
    fun `the level filter hides the less serious messages and keeps the total`() {
        log(LogLevel.DEBUG, "dns")
        log(LogLevel.INFO, "túnel activo")
        log(LogLevel.WARN, "no abrió")
        log(LogLevel.ERROR, "falló")

        viewModel.setMinLevel(LogLevel.WARN)

        assertEquals(listOf("no abrió", "falló"), messages())
        assertEquals(4, viewModel.uiState.value.total)
    }

    @Test
    fun `while paused the list stays as it was and resuming shows what arrived meanwhile`() {
        log(LogLevel.INFO, "uno")
        viewModel.togglePause()

        log(LogLevel.INFO, "dos")
        assertTrue(viewModel.uiState.value.paused)
        assertEquals(listOf("uno"), messages())

        viewModel.togglePause()

        assertFalse(viewModel.uiState.value.paused)
        assertEquals(listOf("uno", "dos"), messages())
    }

    @Test
    fun `the filter still applies while paused`() {
        log(LogLevel.DEBUG, "dns")
        log(LogLevel.ERROR, "falló")
        viewModel.togglePause()

        viewModel.setMinLevel(LogLevel.ERROR)

        assertEquals(listOf("falló"), messages())
    }

    @Test
    fun `clearing empties the log`() {
        log(LogLevel.INFO, "uno")

        viewModel.clear()

        assertEquals(emptyList(), messages())
        assertEquals(0, buffer.entries.value.size)
    }

    @Test
    fun `clearing while paused also empties what is on screen`() {
        log(LogLevel.INFO, "uno")
        viewModel.togglePause()

        viewModel.clear()

        assertEquals(emptyList(), messages())
        assertTrue(viewModel.uiState.value.paused)
    }

    @Test
    fun `the text to copy has one line per visible message`() {
        log(LogLevel.DEBUG, "oculto")
        log(LogLevel.WARN, "visible")
        viewModel.setMinLevel(LogLevel.WARN)

        val lines = viewModel.textToCopy().lines()

        assertEquals(1, lines.size)
        assertTrue(lines.single().endsWith("AVISO Relay: visible"), lines.single())
    }
}

class LogFormatTest {
    private val utc = TimeZone.UTC

    @Test
    fun `the time shows hours minutes seconds and milliseconds`() {
        assertEquals("22:13:20.123", formatLogTime(1_700_000_000_123, utc))
        assertEquals("00:00:00.007", formatLogTime(7, utc))
    }

    @Test
    fun `the time follows the time zone`() {
        assertEquals("17:13:20.123", formatLogTime(1_700_000_000_123, TimeZone.of("America/Havana")))
    }

    @Test
    fun `a line has time and level and tag and message`() {
        val entry = LogEntry(0, 1_700_000_000_123, LogLevel.WARN, "Relay", "El proxy no abrió example.org:443")

        assertEquals("22:13:20.123 AVISO Relay: El proxy no abrió example.org:443", entry.toLine(utc))
    }

    @Test
    fun `levels have a fixed width so the columns line up`() {
        assertEquals(setOf(5), LogLevel.entries.map { it.label().length }.toSet())
    }

    @Test
    fun `several messages become several lines`() {
        val entries = listOf(
            LogEntry(0, 0, LogLevel.INFO, "A", "uno"),
            LogEntry(1, 0, LogLevel.ERROR, "B", "dos"),
        )

        assertEquals(listOf("00:00:00.000 INFO  A: uno", "00:00:00.000 ERROR B: dos"), entries.toText(utc).lines())
    }
}
