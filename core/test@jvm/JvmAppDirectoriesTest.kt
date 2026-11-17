package org.uci.proxy.core

import org.uci.proxy.core.platform.JvmAppDirectories
import org.uci.proxy.core.platform.JvmPlatformInfo
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmAppDirectoriesTest {
    private fun dataDir(osName: String, environment: Map<String, String> = emptyMap()) = JvmAppDirectories(
        platformInfo = JvmPlatformInfo(osName = osName, osVersion = "1.0"),
        userHome = "/home/andrey",
        environment = environment::get,
    ).dataDir

    @Test
    fun `macOS uses Application Support`() {
        assertEquals(Path("/home/andrey", "Library", "Application Support", "ProxyUCI"), dataDir("Mac OS X"))
    }

    @Test
    fun `Windows uses LOCALAPPDATA when present`() {
        val env = mapOf("LOCALAPPDATA" to "/data/local")

        assertEquals(Path("/data/local", "ProxyUCI"), dataDir("Windows 11", env))
    }

    @Test
    fun `Windows falls back to the profile folder`() {
        assertEquals(Path("/home/andrey", "AppData", "Local", "ProxyUCI"), dataDir("Windows 11"))
    }

    @Test
    fun `Linux uses the config folder`() {
        assertEquals(Path("/home/andrey", ".config", "ProxyUCI"), dataDir("Linux"))
    }
}
