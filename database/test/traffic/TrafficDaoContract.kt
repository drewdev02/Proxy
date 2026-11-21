package org.uci.proxy.database.traffic

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.traffic.DailyTraffic
import org.uci.proxy.core.traffic.NamedTraffic
import org.uci.proxy.core.traffic.TrafficUsage
import org.uci.proxy.database.ProxyDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Las mismas pruebas en cada plataforma; cada una aporta cómo abrir una base nueva. */
abstract class TrafficDaoContract {
    protected abstract fun openDatabase(): ProxyDatabase

    private val database by lazy { openDatabase() }
    private val history by lazy { RoomTrafficHistory(database.trafficDao()) }

    @AfterTest
    fun close() {
        database.close()
    }

    private fun usage(exit: RouteExit, host: String?, up: Long, down: Long, app: String? = null) = TrafficUsage(exit, host, app, up, down)

    @Test
    fun `the same destination on the same day adds up`() = runTest {
        history.add(100, listOf(usage(RouteExit.PROXY, "github.com", 1, 10)))
        history.add(100, listOf(usage(RouteExit.PROXY, "github.com", 2, 20), usage(RouteExit.VPN, "netflix.com", 0, 500)))
        history.add(101, listOf(usage(RouteExit.PROXY, "github.com", 0, 7)))

        assertEquals(
            listOf(
                DailyTraffic(100, RouteExit.PROXY, 3, 30),
                DailyTraffic(100, RouteExit.VPN, 0, 500),
                DailyTraffic(101, RouteExit.PROXY, 0, 7),
            ),
            history.daily(fromDay = 100).first().sortedWith(compareBy({ it.day }, { it.exit })),
        )
        assertEquals(listOf(DailyTraffic(101, RouteExit.PROXY, 0, 7)), history.daily(fromDay = 101).first())
    }

    @Test
    fun `destinations are ranked by total and unknown ones keep an empty name`() = runTest {
        history.add(
            100,
            listOf(usage(RouteExit.PROXY, "github.com", 50, 50), usage(RouteExit.VPN, null, 0, 900), usage(RouteExit.PROXY, "eva.uci.cu", 0, 10)),
        )

        assertEquals(
            listOf(NamedTraffic("", 0, 900), NamedTraffic("github.com", 50, 50)),
            history.topHosts(fromDay = 0, limit = 2).first(),
        )
    }

    @Test
    fun `apps leave out the connections without a known app`() = runTest {
        history.add(
            100,
            listOf(usage(RouteExit.PROXY, "web.whatsapp.com", 1, 80, app = "com.whatsapp"), usage(RouteExit.PROXY, "github.com", 0, 500)),
        )

        assertEquals(listOf(NamedTraffic("com.whatsapp", 1, 80)), history.topApps(fromDay = 0, limit = 5).first())
    }

    @Test
    fun `old days are deleted`() = runTest {
        history.add(10, listOf(usage(RouteExit.PROXY, "a.cu", 0, 1)))
        history.add(20, listOf(usage(RouteExit.PROXY, "a.cu", 0, 2)))

        history.deleteBefore(15)

        assertEquals(listOf(DailyTraffic(20, RouteExit.PROXY, 0, 2)), history.daily(fromDay = 0).first())
    }
}
