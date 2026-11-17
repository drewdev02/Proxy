package org.uci.proxy.core.profile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoredProfileRepositoryTest {
    private val keyValueStore = InMemoryKeyValueStore()
    private val secretStore = InMemorySecretStore()
    private val logger = RecordingLogger()
    private val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher())

    private fun newRepository() = StoredProfileRepository(keyValueStore, secretStore, dispatchers, logger)

    private val uci = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val home = ProxyProfile("b2", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, "")

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), newRepository().profiles.value)
    }

    @Test
    fun `saved profiles survive a restart`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")
        repository.save(home, "")

        val reopened = newRepository()

        assertEquals(listOf(uci, home), reopened.profiles.value)
        assertEquals("secreta", reopened.password(uci.id))
    }

    @Test
    fun `the password is never written to the plain store`() = runTest {
        newRepository().save(uci, "secreta")

        assertFalse(keyValueStore.values.values.any { "secreta" in it })
        assertEquals(listOf("secreta"), secretStore.secrets.values.toList())
    }

    @Test
    fun `saving an existing id replaces it in place`() = runTest {
        val repository = newRepository()
        repository.save(uci, "vieja")
        repository.save(home, "")

        repository.save(uci.copy(name = "UCI nuevo", port = 8080), "nueva")

        assertEquals(listOf(uci.copy(name = "UCI nuevo", port = 8080), home), repository.profiles.value)
        assertEquals("nueva", repository.password(uci.id))
    }

    @Test
    fun `saving with an empty password clears the stored one`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")

        repository.save(uci, "")

        assertEquals("", repository.password(uci.id))
        assertTrue(secretStore.secrets.isEmpty())
    }

    @Test
    fun `password of an unknown profile is empty`() = runTest {
        assertEquals("", newRepository().password("no-existe"))
    }

    @Test
    fun `delete removes the profile and its password`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")
        repository.save(home, "otra")

        repository.delete(uci.id)

        assertEquals(listOf(home), repository.profiles.value)
        assertEquals(listOf("otra"), secretStore.secrets.values.toList())
        assertEquals(listOf(home), newRepository().profiles.value)
    }

    @Test
    fun `a failing secret store leaves the profile list untouched`() = runTest {
        val repository = newRepository()
        secretStore.failOnPut = true

        assertFailsWith<IllegalStateException> { repository.save(uci, "secreta") }

        assertEquals(emptyList(), repository.profiles.value)
        assertEquals(emptyList(), newRepository().profiles.value)
    }

    @Test
    fun `corrupt stored data is reported and treated as empty`() {
        keyValueStore.putString("profiles", "{esto no es una lista")

        val repository = newRepository()

        assertEquals(emptyList(), repository.profiles.value)
        assertEquals(listOf(LogLevel.ERROR), logger.entries.map { it.first })
    }
}
