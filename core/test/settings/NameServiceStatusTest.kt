package org.uci.proxy.core.settings

import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class NameServiceStatusTest {
    private val store = InMemoryKeyValueStore()
    private var now = 1_700_000_000_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }

    private fun repository() = StoredNameServiceStatusRepository(store, clock)

    @Test
    fun `nothing is known at first`() {
        assertTrue(repository().statuses.value.isEmpty())
    }

    @Test
    fun `the last result of each service is kept with its time`() {
        val repository = repository()

        repository.report("sslip", NameOutcome.REFUSED)
        now += 5_000
        repository.report("nip", NameOutcome.WORKED)

        assertEquals(NameServiceStatus(NameOutcome.REFUSED, 1_700_000_000_000L), repository.statuses.value["sslip"])
        assertEquals(NameServiceStatus(NameOutcome.WORKED, 1_700_000_005_000L), repository.statuses.value["nip"])
    }

    @Test
    fun `results survive a restart`() {
        repository().report("sslip", NameOutcome.WORKED)

        assertEquals(NameOutcome.WORKED, repository().statuses.value["sslip"]?.outcome)
    }

    @Test
    fun `a different result replaces the previous one at once`() {
        val repository = repository()
        repository.report("sslip", NameOutcome.WORKED)

        now += 1_000
        repository.report("sslip", NameOutcome.REFUSED)

        assertEquals(NameOutcome.REFUSED, repository.statuses.value["sslip"]?.outcome)
    }

    @Test
    fun `the same result in quick succession does not rewrite the store`() {
        val repository = repository()
        repository.report("sslip", NameOutcome.WORKED)
        val first = store.values[StoredNameServiceStatusRepository.KEY]

        now += 10_000
        repository.report("sslip", NameOutcome.WORKED)

        assertEquals(first, store.values[StoredNameServiceStatusRepository.KEY])
        assertEquals(1_700_000_000_000L, repository.statuses.value["sslip"]?.atMillis, "la hora no se mueve")
    }

    @Test
    fun `the same result a minute later refreshes the time`() {
        val repository = repository()
        repository.report("sslip", NameOutcome.WORKED)

        now += StoredNameServiceStatusRepository.MIN_REFRESH_MS
        repository.report("sslip", NameOutcome.WORKED)

        assertEquals(now, repository.statuses.value["sslip"]?.atMillis)
    }

    @Test
    fun `a stored value that makes no sense is ignored`() {
        store.putString(StoredNameServiceStatusRepository.KEY, "no es json")

        assertTrue(repository().statuses.value.isEmpty())
    }
}
