package org.uci.proxy.database.traffic

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_usage WHERE day = :day AND exit = :exit AND host = :host AND app = :app")
    suspend fun find(day: Int, exit: String, host: String, app: String): TrafficUsageRecord?

    @Upsert
    suspend fun upsert(record: TrafficUsageRecord)

    /** Suma cada fila a la que ya hubiera con la misma clave. */
    @Transaction
    suspend fun add(records: List<TrafficUsageRecord>) {
        for (record in records) {
            val old = find(record.day, record.exit, record.host, record.app)
            upsert(if (old == null) record else record.copy(up = old.up + record.up, down = old.down + record.down))
        }
    }

    @Query("SELECT day, exit, SUM(up) AS up, SUM(down) AS down FROM traffic_usage WHERE day >= :fromDay GROUP BY day, exit ORDER BY day")
    fun daily(fromDay: Int): Flow<List<DailyTotalRow>>

    @Query(
        "SELECT host AS name, SUM(up) AS up, SUM(down) AS down FROM traffic_usage WHERE day >= :fromDay " +
            "GROUP BY host ORDER BY SUM(up) + SUM(down) DESC LIMIT :limit",
    )
    fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTotalRow>>

    @Query(
        "SELECT app AS name, SUM(up) AS up, SUM(down) AS down FROM traffic_usage WHERE day >= :fromDay AND app != '' " +
            "GROUP BY app ORDER BY SUM(up) + SUM(down) DESC LIMIT :limit",
    )
    fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTotalRow>>

    @Query("DELETE FROM traffic_usage WHERE day < :day")
    suspend fun deleteBefore(day: Int): Int
}
