package org.uci.proxy.core

import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.core.port.PlatformKind
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPlatformInfoTest {
    private fun kindOf(osName: String) = JvmPlatformInfo(osName = osName, osVersion = "1.0").kind

    @Test
    fun `detects Windows`() {
        assertEquals(PlatformKind.WINDOWS, kindOf("Windows 11"))
    }

    @Test
    fun `detects macOS`() {
        assertEquals(PlatformKind.MACOS, kindOf("Mac OS X"))
    }

    @Test
    fun `Darwin is macOS and not Windows`() {
        assertEquals(PlatformKind.MACOS, kindOf("Darwin"))
    }

    @Test
    fun `anything else is Linux`() {
        assertEquals(PlatformKind.LINUX, kindOf("Linux"))
    }

    @Test
    fun `reads the running system`() {
        val info = JvmPlatformInfo.fromSystemProperties()

        assertEquals(System.getProperty("os.version"), info.osVersion)
    }
}
