package org.uci.proxy.database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** La base va en Application Support, que iOS respalda pero no muestra al usuario. */
@OptIn(ExperimentalForeignApi::class)
fun proxyDatabaseBuilder(): RoomDatabase.Builder<ProxyDatabase> {
    val directory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(directory?.path) { "No se pudo abrir la carpeta Application Support" }
    return Room.databaseBuilder<ProxyDatabase>(name = "$path/$DATABASE_FILE")
}
