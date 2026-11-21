package org.uci.proxy.core

import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.platform.macos.MacLocalDnsSource
import org.uci.proxy.core.platform.windows.WinLocalDnsSource
import org.uci.proxy.core.packet.parseIpv4
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lee el DNS de verdad con las herramientas del sistema (solo lectura: no cambia nada). Comprueba que la orden y su
 * lectura siguen de acuerdo con el sistema real; en CI corre en los equipos de macOS y de Windows.
 */
class LocalDnsSourceLiveTest {
    private val os = System.getProperty("os.name").orEmpty()

    /** En Windows, lo que imprime el guion tal cual, para ver por qué no dio servidores. */
    private fun diagnosis(): String {
        if (!os.startsWith("Windows")) return ""
        val result = ProcessCommandRunner().run(WinLocalDnsSource.COMMAND, null)
        return "\nsalida del guion (código ${result.exitCode}):\n${result.output}"
    }

    @Test
    fun `the real network DNS can be read`() = runBlocking {
        val source = when {
            os.startsWith("Mac") -> MacLocalDnsSource(ProcessCommandRunner())
            os.startsWith("Windows") -> WinLocalDnsSource(ProcessCommandRunner())
            else -> return@runBlocking
        }

        val config = source.read()

        assertTrue(config.servers.isNotEmpty(), "el equipo tiene DNS: $config${diagnosis()}")
        assertTrue(config.servers.all { parseIpv4(it) != null })
    }
}
