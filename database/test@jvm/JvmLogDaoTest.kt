package org.uci.proxy.database

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import org.uci.proxy.database.log.LogDaoContract

class JvmLogDaoTest : LogDaoContract() {
    override fun openDatabase(): ProxyDatabase =
        Room.inMemoryDatabaseBuilder<ProxyDatabase>().buildProxyDatabase(Dispatchers.IO)
}
