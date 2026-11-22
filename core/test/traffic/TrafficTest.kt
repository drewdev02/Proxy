package org.uci.proxy.core.traffic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.uci.proxy.core.model.RouteExit
import org.uci.proxy.core.profile.AppDispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TrafficMeterTest {
    @Test
    fun `bytes are summed by exit destination and app until drained`() = runTest {
        val meter = TrafficMeter()
        meter.count(RouteExit.PROXY, "GitHub.com", null, up = 10, down = 100)
        meter.count(RouteExit.PROXY, "github.com", null, up = 5, down = 0)
        meter.count(RouteExit.VPN, "netflix.com", null, up = 0, down = 1_000)

        val delta = meter.drain(1_000)

        assertEquals(15, delta.up)
        assertEquals(1_100, delta.down)
        assertEquals(
            listOf(TrafficUsage(RouteExit.PROXY, "github.com", null, 15, 100), TrafficUsage(RouteExit.VPN, "netflix.com", null, 0, 1_000)),
            delta.usage,
        )
        assertTrue(meter.drain(1_000).usage.isEmpty())
    }

    @Test
    fun `open connections are counted and never go below zero`() = runTest {
        val meter = TrafficMeter()
        meter.opened()
        meter.opened()
        meter.closed()
        assertEquals(1, meter.drain(1_000).connections)
        meter.closed()
        meter.closed()
        assertEquals(0, meter.drain(1_000).connections)
    }
}

class TrafficStoreTest {
    @Test
    fun `each measure becomes a rate per second and adds to the session`() {
        val store = TrafficStore(history = 3)
        store.record(TrafficDelta(millis = 500, up = 100, down = 1_000, connections = 2))
        store.record(TrafficDelta(millis = 1_000, up = 0, down = 3_000, connections = 1))

        val live = store.live.value
        assertEquals(listOf(2_000L, 3_000L), live.downRates)
        assertEquals(listOf(200L, 0L), live.upRates)
        assertEquals(4_100, live.sessionTotal)
        assertEquals(1, live.connections)
    }

    @Test
    fun `only the last minute of rates is kept and a new session starts from zero`() {
        val store = TrafficStore(history = 2)
        repeat(3) { store.record(TrafficDelta(1_000, up = it.toLong(), down = 0, connections = 0)) }
        assertEquals(listOf(1L, 2L), store.live.value.upRates)

        store.reset()
        assertFalse(store.live.value.measured)
        assertEquals(0, store.live.value.sessionTotal)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficRecorderTest {
    private class RecordingHistory : TrafficHistory {
        val added = mutableListOf<Pair<Int, List<TrafficUsage>>>()
        var deletedBefore: Int? = null

        override suspend fun add(day: Int, usage: List<TrafficUsage>) {
            added += day to usage
        }

        override fun daily(fromDay: Int): Flow<List<DailyTraffic>> = emptyFlow()

        override fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> = emptyFlow()

        override fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTraffic>> = emptyFlow()

        override suspend fun deleteBefore(day: Int) {
            deletedBefore = day
        }
    }

    private var now = Instant.parse("2026-11-21T23:59:50Z")
    private val clock = object : Clock {
        override fun now() = this@TrafficRecorderTest.now
    }

    @Test
    fun `measures are summed per day and saved together`() = runTest {
        val store = TrafficStore()
        val history = RecordingHistory()
        val recorder = TrafficRecorder(store, history, clock, AppDispatchers(StandardTestDispatcher(testScheduler)), timeZone = { TimeZone.UTC })
        val job = recorder.start()
        runCurrent()
        val today = recorder.today()
        assertEquals(today - TrafficRecorder.RETENTION_DAYS, history.deletedBefore, "lo de hace más de 90 días se borra")

        store.record(TrafficDelta(1_000, up = 1, down = 10, connections = 1, usage = listOf(TrafficUsage(RouteExit.PROXY, "a.cu", null, 1, 10))))
        store.record(TrafficDelta(1_000, up = 2, down = 20, connections = 1, usage = listOf(TrafficUsage(RouteExit.PROXY, "a.cu", null, 2, 20))))
        runCurrent()
        // Pasada la medianoche, lo nuevo va al día siguiente.
        now = Instant.parse("2026-11-22T00:00:10Z")
        store.record(TrafficDelta(1_000, up = 0, down = 5, connections = 1, usage = listOf(TrafficUsage(RouteExit.VPN, "b.com", null, 0, 5))))
        runCurrent()
        recorder.saveNow()

        assertEquals(
            listOf(
                today to listOf(TrafficUsage(RouteExit.PROXY, "a.cu", null, 3, 30)),
                today + 1 to listOf(TrafficUsage(RouteExit.VPN, "b.com", null, 0, 5)),
            ),
            history.added,
        )
        job.cancel()
    }
}
