package org.uci.proxy.database.log

import org.uci.proxy.core.log.LogEntry
import org.uci.proxy.core.port.LogHistory

/** El [LogHistory] de verdad: la tabla `log_record`. */
class RoomLogHistory(private val dao: LogDao) : LogHistory {
    override suspend fun append(entries: List<LogEntry>) =
        dao.insert(entries.map { LogRecord(epochMillis = it.epochMillis, level = it.level, tag = it.tag, message = it.message) })

    override suspend fun latest(limit: Int): List<LogEntry> =
        dao.latest(limit).map { LogEntry(it.id, it.epochMillis, it.level, it.tag, it.message) }

    override suspend fun deleteBefore(epochMillis: Long) {
        dao.deleteOlderThan(epochMillis)
    }

    override suspend fun trimTo(maxEntries: Int) {
        dao.trimTo(maxEntries)
    }
}
