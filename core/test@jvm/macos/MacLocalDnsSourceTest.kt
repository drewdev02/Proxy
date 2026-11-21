package org.uci.proxy.core.macos

import kotlinx.coroutines.runBlocking
import org.uci.proxy.core.platform.CommandResult
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.platform.macos.MacLocalDnsSource
import org.uci.proxy.core.port.LocalDnsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

private const val GLOBAL_IPV4 = """<dictionary> {
  PrimaryInterface : en6
  PrimaryService : A23605B4-2DEF-4357-A7A9-425EF505BE28
  Router : 10.8.40.254
}
"""

private const val SERVICE_DNS = """<dictionary> {
  DomainName : uci.cu
  SearchDomains : <array> {
    0 : uci.cu
    1 : prod.uci.cu
  }
  ServerAddresses : <array> {
    0 : 10.0.0.4
    1 : 10.0.0.3
  }
}
"""

private const val NO_SUCH_KEY = "  No such key\n"

/** Contesta a cada `show <clave>` de scutil con lo que diga [answers]; lo demás, «No such key». */
private class ScutilRunner(private val answers: Map<String, String>) : CommandRunner {
    val shown = mutableListOf<String>()

    override fun run(command: List<String>, input: String?): CommandResult {
        val key = input.orEmpty().lineSequence().first().removePrefix("show ").trim()
        shown += key
        return CommandResult(0, answers[key] ?: NO_SUCH_KEY)
    }
}

class MacLocalDnsSourceTest {
    private val service = "A23605B4-2DEF-4357-A7A9-425EF505BE28"

    @Test
    fun `the primary service's DNS is read, not the global one`() = runBlocking {
        val runner = ScutilRunner(
            mapOf(
                "State:/Network/Global/IPv4" to GLOBAL_IPV4,
                "State:/Network/Service/$service/DNS" to SERVICE_DNS,
            ),
        )

        val config = MacLocalDnsSource(runner).read()

        assertEquals(listOf("10.0.0.4", "10.0.0.3"), config.servers)
        assertEquals(listOf("uci.cu", "prod.uci.cu"), config.searchDomains)
        assertEquals(listOf("State:/Network/Global/IPv4", "State:/Network/Service/$service/DNS", "Setup:/Network/Service/$service/DNS"), runner.shown)
    }

    @Test
    fun `servers set by hand are used when the state has none`() = runBlocking {
        val runner = ScutilRunner(
            mapOf(
                "State:/Network/Global/IPv4" to GLOBAL_IPV4,
                "State:/Network/Service/$service/DNS" to "<dictionary> {\n  DomainName : uci.cu\n}\n",
                "Setup:/Network/Service/$service/DNS" to "<dictionary> {\n  ServerAddresses : <array> {\n    0 : 10.0.0.3\n  }\n}\n",
            ),
        )

        val config = MacLocalDnsSource(runner).read()

        assertEquals(listOf("10.0.0.3"), config.servers)
        assertEquals(listOf("uci.cu"), config.searchDomains)
    }

    @Test
    fun `without a network there is nothing to read`() = runBlocking {
        assertEquals(LocalDnsConfig.NONE, MacLocalDnsSource(ScutilRunner(emptyMap())).read())
    }

    @Test
    fun `a strange service id is not put into a scutil script`() = runBlocking {
        val runner = ScutilRunner(mapOf("State:/Network/Global/IPv4" to "<dictionary> {\n  PrimaryService : x;remove Foo\n}\n"))

        assertEquals(LocalDnsConfig.NONE, MacLocalDnsSource(runner).read())
        assertEquals(1, runner.shown.size)
    }
}
