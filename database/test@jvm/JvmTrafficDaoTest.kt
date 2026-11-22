package org.uci.proxy.database

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import org.uci.proxy.database.traffic.TrafficDaoContract

class JvmTrafficDaoTest : TrafficDaoContract() {
    override fun openDatabase(): ProxyDatabase =
        Room.inMemoryDatabaseBuilder<ProxyDatabase>().buildProxyDatabase(Dispatchers.IO)
}
