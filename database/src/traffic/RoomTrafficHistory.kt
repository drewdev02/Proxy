package org.uci.proxy.database.traffic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.model.DailyTraffic
import org.uci.proxy.core.model.NamedTraffic
import org.uci.proxy.core.port.TrafficHistory
import org.uci.proxy.core.model.TrafficUsage

/** El [TrafficHistory] de verdad: la tabla `traffic_usage`. */
class RoomTrafficHistory(private val dao: TrafficDao) : TrafficHistory {
    override suspend fun add(day: Int, usage: List<TrafficUsage>) =
        dao.add(usage.map { TrafficUsageRecord(day, it.exit.name, it.host.orEmpty(), it.app.orEmpty(), it.up, it.down) })

    override fun daily(fromDay: Int): Flow<List<DailyTraffic>> = dao.daily(fromDay).map { rows ->
        // Una salida que esta versión no conoce (de una versión más nueva) no rompe la pantalla: se omite.
        rows.mapNotNull { row -> RouteExit.entries.firstOrNull { it.name == row.exit }?.let { DailyTraffic(row.day, it, row.up, row.down) } }
    }

    override fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> =
        dao.topHosts(fromDay, limit).map { rows -> rows.map { NamedTraffic(it.name, it.up, it.down) } }

    override fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> =
        dao.topApps(fromDay, limit).map { rows -> rows.map { NamedTraffic(it.name, it.up, it.down) } }

    override suspend fun deleteBefore(day: Int) {
        dao.deleteBefore(day)
    }
}
