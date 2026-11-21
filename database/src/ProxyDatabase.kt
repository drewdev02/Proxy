package org.uci.proxy.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import org.uci.proxy.database.log.LogDao
import org.uci.proxy.database.log.LogRecord
import org.uci.proxy.database.traffic.TrafficDao
import org.uci.proxy.database.traffic.TrafficUsageRecord

@Database(entities = [LogRecord::class, TrafficUsageRecord::class], version = 2, exportSchema = false)
@ConstructedBy(ProxyDatabaseConstructor::class)
abstract class ProxyDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    abstract fun trafficDao(): TrafficDao
}

/** Versión 2: el consumo por día (`traffic_usage`). El registro guardado se conserva. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `traffic_usage` (`day` INTEGER NOT NULL, `exit` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                "`app` TEXT NOT NULL, `up` INTEGER NOT NULL, `down` INTEGER NOT NULL, PRIMARY KEY(`day`, `exit`, `host`, `app`))",
        )
    }
}

/** Room genera la implementación en cada plataforma con KSP. */
@Suppress("KotlinNoActualForExpect")
expect object ProxyDatabaseConstructor : RoomDatabaseConstructor<ProxyDatabase> {
    override fun initialize(): ProxyDatabase
}

const val DATABASE_FILE = "proxyuci.db"

/**
 * Termina de configurar la base igual en todas las plataformas: el SQLite que va dentro de la app (misma versión en
 * Android, macOS, Windows e iOS) y las consultas en [background].
 */
fun RoomDatabase.Builder<ProxyDatabase>.buildProxyDatabase(background: CoroutineDispatcher): ProxyDatabase =
    setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .setQueryCoroutineContext(background)
        .build()
