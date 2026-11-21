package org.uci.proxy.core.windows

import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.platform.CommandResult
import org.uci.proxy.core.platform.windows.WinLocalDnsSource
import org.uci.proxy.core.port.LocalDnsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinLocalDnsSourceTest {
    @Test
    fun `the script output becomes servers and domains`() = runBlocking {
        val output = "server=10.0.0.3\r\nserver=10.0.0.4\r\nserver=fec0:0:0:ffff::1\r\ndomain=uci.cu\r\ndomain=UCI.CU\r\n\r\n"
        var command = emptyList<String>()

        val config = WinLocalDnsSource { cmd, _ -> command = cmd; CommandResult(0, output) }.read()

        assertEquals(LocalDnsConfig(listOf("10.0.0.3", "10.0.0.4"), listOf("uci.cu")), config)
        assertEquals("powershell.exe", command.first())
        assertTrue("ProxyUCI*" in command.last(), "sin contar el adaptador del túnel")
    }

    @Test
    fun `a failing script reads as nothing`() = runBlocking {
        assertEquals(LocalDnsConfig.NONE, WinLocalDnsSource { _, _ -> CommandResult(1, "server=10.0.0.3") }.read())
    }
}
