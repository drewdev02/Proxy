package org.uci.proxy.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileListViewModelTest : MainDispatcherTest() {
    private val uci = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val home = ProxyProfile("b2", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, "")
    private val repository = FakeProfileRepository(listOf(uci, home))
    private val logger = RecordingLogger()
    private val viewModel = ProfileListViewModel(repository, logger)

    @Test
    fun `shows the stored profiles`() {
        assertEquals(listOf(uci, home), viewModel.profiles.value)
    }

    @Test
    fun `delete removes the profile from the list`() {
        viewModel.delete(uci.id)

        assertEquals(listOf(home), viewModel.profiles.value)
        assertFalse(viewModel.deleteFailed.value)
    }

    @Test
    fun `a failed delete is shown and logged and keeps the list`() {
        repository.failWrites = true

        viewModel.delete(uci.id)

        assertTrue(viewModel.deleteFailed.value)
        assertEquals(listOf(uci, home), viewModel.profiles.value)
        assertEquals(listOf(LogLevel.ERROR), logger.entries.map { it.first })
    }

    @Test
    fun `the delete error can be dismissed`() {
        repository.failWrites = true
        viewModel.delete(uci.id)

        viewModel.dismissDeleteError()

        assertFalse(viewModel.deleteFailed.value)
    }
}
