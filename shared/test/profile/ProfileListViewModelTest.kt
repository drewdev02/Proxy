package org.uci.proxy.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.di.logStartup
import org.uci.proxy.fakes.FakeProfileRepository
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.fakes.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlatformInfo(
    override val kind: PlatformKind = PlatformKind.IOS,
    override val osVersion: String = "18.1",
) : PlatformInfo

class ProfileListViewModelTest : MainDispatcherTest() {
    private val uci = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val home = ProxyProfile("b2", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, "")
    private val repository = FakeProfileRepository(listOf(uci, home))
    private val tunnel = FakeTunnel()
    private val logger = RecordingLogger()
    // Perezoso: su uiState arranca en viewModelScope, que necesita el Main de pruebas que se instala antes de cada prueba.
    private val viewModel by lazy { ProfileListViewModel(repository, tunnel, tunnel, FakePlatformInfo(), logger) }

    private val state get() = viewModel.uiState.value

    @Test
    fun `shows the stored profiles and the platform`() {
        assertEquals(listOf(uci, home), state.profiles)
        assertEquals("ProxyUCI en iOS 18.1", state.platformLabel)
    }

    @Test
    fun `delete removes the profile from the list`() {
        viewModel.delete(uci.id)

        assertEquals(listOf(home), state.profiles)
        assertFalse(state.deleteFailed)
    }

    @Test
    fun `a failed delete is shown and logged and keeps the list`() {
        repository.failWrites = true

        viewModel.delete(uci.id)

        assertTrue(state.deleteFailed)
        assertEquals(listOf(uci, home), state.profiles)
        assertEquals(listOf(LogLevel.ERROR), logger.entries.map { it.first })
    }

    @Test
    fun `the delete error can be dismissed`() {
        repository.failWrites = true
        viewModel.delete(uci.id)

        viewModel.dismissDeleteError()

        assertFalse(state.deleteFailed)
    }

    @Test
    fun `connect and disconnect drive the tunnel and the state follows`() {
        viewModel.connect(home.id)

        assertEquals(listOf(home.id), tunnel.connectRequests)
        assertEquals(home.id, state.tunnel.activeProfileId)

        viewModel.disconnect()

        assertEquals(TunnelState.Disconnected, state.tunnel.state)
    }

    @Test
    fun `without a tunnel on this platform connecting is not offered`() {
        val noTunnel = FakeTunnel(isAvailable = false)
        val vm = ProfileListViewModel(repository, noTunnel, noTunnel, FakePlatformInfo(), logger)

        assertFalse(vm.uiState.value.tunnel.isAvailable)
    }

    @Test
    fun `the view model does not log on its own`() {
        // La línea de arranque la escribe initKoin una vez por proceso, no cada vez que se abre la pestaña.
        assertTrue(logger.entries.isEmpty())
    }
}

class TunnelUiModelTest {
    @Test
    fun `the active profile is the one connecting or connected`() {
        assertEquals("p1", TunnelUiModel(true, TunnelState.Connecting("p1")).activeProfileId)
        assertEquals("p2", TunnelUiModel(true, TunnelState.Connected("p2")).activeProfileId)
        assertNull(TunnelUiModel(true, TunnelState.Disconnected).activeProfileId)
    }
}

class StartupLogTest {
    @Test
    fun `startup logs the platform once`() {
        val logger = RecordingLogger()

        logStartup(FakePlatformInfo(PlatformKind.WINDOWS, "11"), logger)

        assertEquals(listOf("ProxyUCI en Windows 11"), logger.entries.map { it.second })
    }
}
