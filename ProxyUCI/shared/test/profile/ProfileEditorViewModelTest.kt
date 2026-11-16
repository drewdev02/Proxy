package org.uci.proxy.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileEditorViewModelTest : MainDispatcherTest() {
    private val uci = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val repository = FakeProfileRepository(listOf(uci)).apply { passwords[uci.id] = "secreta" }
    private val logger = RecordingLogger()

    private fun newViewModel() =
        ProfileEditorViewModel(repository, ProfileValidator(), idGenerator = { "nuevo-id" }, logger)

    private val validDraft = ProfileDraft("Casa", "10.0.0.1", "1080", ProxyProtocol.SOCKS5, "")

    @Test
    fun `starting without id shows an empty form for a new profile`() {
        val viewModel = newViewModel()

        viewModel.start(profileId = null)

        assertEquals(ProfileEditorUiState(isNew = true), viewModel.uiState.value)
    }

    @Test
    fun `starting with an id loads the profile and its password`() {
        val viewModel = newViewModel()

        viewModel.start(uci.id)

        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertEquals(ProfileDraft("UCI", "proxy.uci.cu", "3128", ProxyProtocol.HTTP, "andrey"), state.draft)
        assertEquals("secreta", state.password)
    }

    @Test
    fun `starting again clears what the previous edit left`() {
        val viewModel = newViewModel()
        viewModel.start(uci.id)

        viewModel.start(profileId = null)

        assertEquals(ProfileEditorUiState(isNew = true), viewModel.uiState.value)
    }

    @Test
    fun `saving a valid new profile stores it with a generated id`() {
        val viewModel = newViewModel()
        viewModel.start(profileId = null)
        viewModel.onDraftChange(validDraft)
        viewModel.onPasswordChange("clave")

        viewModel.save()

        val saved = ProxyProfile("nuevo-id", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, "")
        assertEquals(listOf(uci, saved), repository.profiles.value)
        assertEquals("clave", repository.passwords["nuevo-id"])
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `saving an edited profile keeps its id`() {
        val viewModel = newViewModel()
        viewModel.start(uci.id)
        viewModel.onDraftChange(viewModel.uiState.value.draft.copy(port = "8080"))

        viewModel.save()

        assertEquals(listOf(uci.copy(port = 8080)), repository.profiles.value)
        assertEquals("secreta", repository.passwords[uci.id])
    }

    @Test
    fun `saving an invalid draft shows the errors and stores nothing`() {
        val viewModel = newViewModel()
        viewModel.start(profileId = null)
        viewModel.onDraftChange(validDraft.copy(host = "http://proxy", port = "abc"))

        viewModel.save()

        val state = viewModel.uiState.value
        assertEquals(setOf(ProfileField.HOST, ProfileField.PORT), state.errors)
        assertFalse(state.saved)
        assertEquals(listOf(uci), repository.profiles.value)
    }

    @Test
    fun `editing a field clears only that field's error`() {
        val viewModel = newViewModel()
        viewModel.start(profileId = null)
        viewModel.onDraftChange(validDraft.copy(host = "", port = "abc"))
        viewModel.save()

        viewModel.onDraftChange(viewModel.uiState.value.draft.copy(host = "10.0.0.1"))

        assertEquals(setOf(ProfileField.PORT), viewModel.uiState.value.errors)
    }

    @Test
    fun `a storage failure is shown and logged instead of closing the form`() {
        val viewModel = newViewModel()
        viewModel.start(profileId = null)
        viewModel.onDraftChange(validDraft)
        repository.failWrites = true

        viewModel.save()

        val state = viewModel.uiState.value
        assertTrue(state.saveFailed)
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertEquals(listOf(LogLevel.ERROR), logger.entries.map { it.first })
    }

    @Test
    fun `changing the form after a failure hides the failure message`() {
        val viewModel = newViewModel()
        viewModel.start(profileId = null)
        viewModel.onDraftChange(validDraft)
        repository.failWrites = true
        viewModel.save()

        viewModel.onPasswordChange("otra")

        assertFalse(viewModel.uiState.value.saveFailed)
    }
}
