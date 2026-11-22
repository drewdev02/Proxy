package org.uci.proxy.core.profile

import org.uci.proxy.core.data.StoredActiveProfileStore
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredActiveProfileStoreTest {
    @Test
    fun `the choice survives a new store on the same storage`() {
        val storage = InMemoryKeyValueStore()
        StoredActiveProfileStore(storage).select("p2")

        assertEquals("p2", StoredActiveProfileStore(storage).activeProfileId.value)
    }

    @Test
    fun `nothing is chosen at first`() {
        assertNull(StoredActiveProfileStore(InMemoryKeyValueStore()).activeProfileId.value)
    }
}

class ResolveActiveProfileIdTest {
    private val ids = listOf("p1", "p2", "p3")

    @Test
    fun `the profile in the tunnel wins over the chosen one`() {
        assertEquals("p3", resolveActiveProfileId(ids, chosen = "p2", inTunnel = "p3"))
    }

    @Test
    fun `without tunnel the chosen profile is used`() {
        assertEquals("p2", resolveActiveProfileId(ids, chosen = "p2", inTunnel = null))
    }

    @Test
    fun `a deleted choice falls back to the first profile`() {
        assertEquals("p1", resolveActiveProfileId(ids, chosen = "gone", inTunnel = null))
    }

    @Test
    fun `no profiles means no active profile`() {
        assertNull(resolveActiveProfileId(emptyList(), chosen = "p1", inTunnel = "p1"))
    }
}
