package org.uci.proxy.database

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.uci.proxy.database.log.LogDaoContract

class IosLogDaoTest : LogDaoContract() {
    override fun openDatabase(): ProxyDatabase =
        Room.inMemoryDatabaseBuilder<ProxyDatabase>().buildProxyDatabase(Dispatchers.IO)
}
