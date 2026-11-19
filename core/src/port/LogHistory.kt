package org.uci.proxy.core.port

import org.uci.proxy.core.log.LogEntry

/**
 * El registro guardado en disco, para verlo después de cerrar la app. Los [LogEntry.id] que devuelve no son los del
 * búfer: quien lo lea debe renumerarlos (ver LogBuffer.restore).
 */
interface LogHistory {
    suspend fun append(entries: List<LogEntry>)

    /** Las [limit] líneas más recientes, de la más antigua a la más nueva. */
    suspend fun latest(limit: Int): List<LogEntry>

    /** Borra las líneas anteriores a [epochMillis]. */
    suspend fun deleteBefore(epochMillis: Long)

    /** Deja solo las [maxEntries] líneas más recientes. */
    suspend fun trimTo(maxEntries: Int)
}
