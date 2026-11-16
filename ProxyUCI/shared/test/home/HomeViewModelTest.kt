package org.uci.proxy.home

import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeViewModelTest {
    private class FakePlatformInfo(
        override val kind: PlatformKind,
        override val osVersion: String,
    ) : PlatformInfo

    private class RecordingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun log(level: LogLevel, tag: String, message: String) {
            messages += message
        }
    }

    @Test
    fun `shows the injected platform`() {
        val viewModel = HomeViewModel(FakePlatformInfo(PlatformKind.IOS, "18.1"), RecordingLogger())

        assertEquals(HomeUiState(platformName = "iOS", osVersion = "18.1"), viewModel.uiState)
    }

    @Test
    fun `logs the platform once on start`() {
        val logger = RecordingLogger()

        HomeViewModel(FakePlatformInfo(PlatformKind.WINDOWS, "11"), logger)

        assertEquals(listOf("ProxyUCI en Windows 11"), logger.messages)
    }
}
