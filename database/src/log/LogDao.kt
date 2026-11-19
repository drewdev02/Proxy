package org.uci.proxy.database.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LogDao {
    @Insert
    suspend fun insert(records: List<LogRecord>)

    /** Las [limit] líneas más recientes, de la más antigua a la más nueva. */
    @Query("SELECT * FROM (SELECT * FROM log_record ORDER BY id DESC LIMIT :limit) ORDER BY id")
    suspend fun latest(limit: Int): List<LogRecord>

    /** Borra lo anterior a [epochMillis] y devuelve cuántas líneas quitó. */
    @Query("DELETE FROM log_record WHERE epochMillis < :epochMillis")
    suspend fun deleteOlderThan(epochMillis: Long): Int

    /** Deja solo las [keep] líneas más recientes. */
    @Query("DELETE FROM log_record WHERE id NOT IN (SELECT id FROM log_record ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int

    @Query("SELECT COUNT(*) FROM log_record")
    suspend fun count(): Int
}
