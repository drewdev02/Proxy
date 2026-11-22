package org.uci.proxy.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.uci.proxy.core.model.DailyTraffic
import org.uci.proxy.core.model.NamedTraffic
import org.uci.proxy.core.port.TrafficHistory
import org.uci.proxy.core.model.TrafficUsage

/** El consumo guardado, en memoria: suma igual que la tabla de verdad. */
class FakeTrafficHistory : TrafficHistory {
    private data class Row(val day: Int, val usage: TrafficUsage)

    private val rows = MutableStateFlow<List<Row>>(emptyList())

    override suspend fun add(day: Int, usage: List<TrafficUsage>) {
        rows.value = rows.value + usage.map { Row(day, it) }
    }

    override fun daily(fromDay: Int): Flow<List<DailyTraffic>> = rows.map { all ->
        all.filter { it.day >= fromDay }
            .groupBy { it.day to it.usage.exit }
            .map { (key, list) -> DailyTraffic(key.first, key.second, list.sumOf { it.usage.up }, list.sumOf { it.usage.down }) }
            .sortedBy { it.day }
    }

    override fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> = ranking(fromDay, limit, keepEmpty = true) { it.host.orEmpty() }

    override fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> = ranking(fromDay, limit, keepEmpty = false) { it.app.orEmpty() }

    override suspend fun deleteBefore(day: Int) {
        rows.value = rows.value.filter { it.day >= day }
    }

    private fun ranking(fromDay: Int, limit: Int, keepEmpty: Boolean, key: (TrafficUsage) -> String): Flow<List<NamedTraffic>> = rows.map { all ->
        all.filter { it.day >= fromDay }
            .map { key(it.usage) to it.usage }
            .filter { keepEmpty || it.first.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .map { (name, list) -> NamedTraffic(name, list.sumOf { it.up }, list.sumOf { it.down }) }
            .sortedByDescending { it.total }
            .take(limit)
    }
}
