package org.uci.proxy.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import org.uci.proxy.database.log.LogDao
import org.uci.proxy.database.log.LogRecord

@Database(entities = [LogRecord::class], version = 1, exportSchema = false)
@ConstructedBy(ProxyDatabaseConstructor::class)
abstract class ProxyDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
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
        .setQueryCoroutineContext(background)
        .build()
