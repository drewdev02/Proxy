package org.uci.proxy.database

import androidx.room.Room
import androidx.room.RoomDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** [dataDir] es la carpeta de datos de la app (JvmAppDirectories.dataDir); se crea si no existe. */
fun proxyDatabaseBuilder(dataDir: Path): RoomDatabase.Builder<ProxyDatabase> {
    dataDir.createDirectories()
    return Room.databaseBuilder<ProxyDatabase>(name = dataDir.resolve(DATABASE_FILE).toAbsolutePath().toString())
}
