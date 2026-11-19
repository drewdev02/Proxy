package org.uci.proxy.database.log

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.uci.proxy.core.port.LogLevel

/** Una línea del registro guardada en disco. Room guarda [level] por su nombre. */
@Entity(tableName = "log_record", indices = [Index("epochMillis")])
data class LogRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
