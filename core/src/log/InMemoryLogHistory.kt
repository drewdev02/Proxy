package org.uci.proxy.core.log

import org.uci.proxy.core.model.LogEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.uci.proxy.core.port.LogHistory

/** Un [LogHistory] que no sale de la memoria: para pruebas y para donde no haya base de datos. */
class InMemoryLogHistory : LogHistory {
    private val lock = Mutex()
    private val stored = mutableListOf<LogEntry>()

    /** Copia de lo guardado, del más antiguo al más reciente. */
    suspend fun all(): List<LogEntry> = lock.withLock { stored.toList() }

    override suspend fun append(entries: List<LogEntry>) = lock.withLock { stored += entries; Unit }

    override suspend fun latest(limit: Int): List<LogEntry> = lock.withLock { stored.takeLast(limit) }

    override suspend fun deleteBefore(epochMillis: Long) = lock.withLock { stored.removeAll { it.epochMillis < epochMillis }; Unit }

    override suspend fun trimTo(maxEntries: Int) = lock.withLock {
        if (stored.size > maxEntries) stored.subList(0, stored.size - maxEntries).clear()
    }
}
