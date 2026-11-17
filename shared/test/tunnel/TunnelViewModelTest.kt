package org.uci.proxy.tunnel

import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.fakes.MainDispatcherTest
import org.uci.proxy.profile.TunnelUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelViewModelTest : MainDispatcherTest() {
    private val tunnel = FakeTunnel()
    private val viewModel = TunnelViewModel(control = tunnel, monitor = tunnel)

    @Test
    fun `starts disconnected and reflects availability`() {
        assertEquals(TunnelState.Disconnected, viewModel.state.value)
        assertTrue(viewModel.isAvailable)
        assertFalse(TunnelViewModel(FakeTunnel(isAvailable = false), tunnel).isAvailable)
    }

    @Test
    fun `connect asks the tunnel for that profile and the state follows`() {
        viewModel.connect("p1")

        assertEquals(listOf("p1"), tunnel.connectRequests)
        assertEquals(TunnelState.Connected("p1"), viewModel.state.value)
    }

    @Test
    fun `disconnect returns to disconnected`() {
        viewModel.connect("p1")

        viewModel.disconnect()

        assertEquals(TunnelState.Disconnected, viewModel.state.value)
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
