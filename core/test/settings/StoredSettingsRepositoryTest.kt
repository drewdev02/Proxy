package org.uci.proxy.core.settings

import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredSettingsRepositoryTest {
    private val store = InMemoryKeyValueStore()

    @Test
    fun `answering connectivity checks is on by default`() {
        assertTrue(AppSettings().answerConnectivityChecks)
        assertTrue(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)
    }

    @Test
    fun `a change is published and survives a restart`() {
        val repository = StoredSettingsRepository(store)

        repository.setAnswerConnectivityChecks(false)

        assertFalse(repository.settings.value.answerConnectivityChecks)
        assertFalse(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)

        repository.setAnswerConnectivityChecks(true)
        assertTrue(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)
    }

    @Test
    fun `a stored value that makes no sense falls back to the default`() {
        store.putString(StoredSettingsRepository.ANSWER_CONNECTIVITY_CHECKS_KEY, "quizás")

        assertEquals(AppSettings(), StoredSettingsRepository(store).settings.value)
    }
}
