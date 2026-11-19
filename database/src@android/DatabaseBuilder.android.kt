package org.uci.proxy.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun proxyDatabaseBuilder(context: Context): RoomDatabase.Builder<ProxyDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<ProxyDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(DATABASE_FILE).absolutePath,
    )
}
