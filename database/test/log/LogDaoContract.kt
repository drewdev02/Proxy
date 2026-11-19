package org.uci.proxy.database.log

import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.database.ProxyDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Las mismas pruebas en cada plataforma; cada una aporta cómo abrir una base nueva. */
abstract class LogDaoContract {
    protected abstract fun openDatabase(): ProxyDatabase

    private val database by lazy { openDatabase() }
    private val history by lazy { RoomLogHistory(database.logDao()) }

    @AfterTest
    fun close() {
        database.close()
    }

    @Test
    fun `latest lines come back oldest first and limited`() = runTest {
        history.append((1..5).map { entry(epochMillis = it.toLong(), message = "línea $it") })

        val latest = history.latest(limit = 3)

        assertEquals(listOf("línea 3", "línea 4", "línea 5"), latest.map { it.message })
    }

    @Test
    fun `level tag and time survive the round trip`() = runTest {
        history.append(listOf(LogEntry(99, 1_700_000_000_123, LogLevel.ERROR, "Relay", "falló ñ")))

        val saved = history.latest(1).single()

        assertEquals(LogEntry(saved.id, 1_700_000_000_123, LogLevel.ERROR, "Relay", "falló ñ"), saved)
    }

    @Test
    fun `old lines are deleted`() = runTest {
        history.append((1..4).map { entry(epochMillis = it * 1_000L, message = "$it") })

        history.deleteBefore(3_000L)

        assertEquals(listOf("3", "4"), history.latest(10).map { it.message })
    }

    @Test
    fun `trimming keeps only the most recent lines`() = runTest {
        history.append((1..5).map { entry(epochMillis = it.toLong(), message = "$it") })

        history.trimTo(2)

        assertEquals(listOf("4", "5"), history.latest(10).map { it.message })
        assertEquals(2, database.logDao().count())
    }

    private fun entry(epochMillis: Long, message: String) = LogEntry(0, epochMillis, LogLevel.INFO, "Prueba", message)
}
