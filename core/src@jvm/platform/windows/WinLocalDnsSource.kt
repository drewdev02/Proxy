package org.uci.proxy.core.platform.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource

/**
 * El DNS del adaptador por el que sale hoy el tráfico (el de la ruta por defecto con menor métrica, sin contar el del
 * túnel), leído con PowerShell, más la lista de sufijos de búsqueda del equipo.
 */
class WinLocalDnsSource(private val runner: CommandRunner) : LocalDnsSource {
    override suspend fun read(): LocalDnsConfig = withContext(Dispatchers.IO) {
        val result = runner.run(listOf(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", SCRIPT), null)
        if (result.exitCode != 0) LocalDnsConfig.NONE else parse(result.output)
    }

    internal companion object {
        private const val POWERSHELL = "powershell.exe"

        // Una línea por dato, `server=` o `domain=`: fácil de leer y de probar sin Windows.
        val SCRIPT = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            ${'$'}route = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' |
                Where-Object { ${'$'}_.InterfaceAlias -notlike '${WinPlatformTunnel.ADAPTER_NAME}*' } |
                Sort-Object { ${'$'}_.RouteMetric + ${'$'}_.InterfaceMetric } | Select-Object -First 1
            if (${'$'}route) {
                (Get-DnsClientServerAddress -InterfaceIndex ${'$'}route.InterfaceIndex -AddressFamily IPv4).ServerAddresses |
                    ForEach-Object { "server=${'$'}_" }
                (Get-DnsClient -InterfaceIndex ${'$'}route.InterfaceIndex).ConnectionSpecificSuffix |
                    Where-Object { ${'$'}_ } | ForEach-Object { "domain=${'$'}_" }
            }
            (Get-DnsClientGlobalSetting).SuffixSearchList | Where-Object { ${'$'}_ } | ForEach-Object { "domain=${'$'}_" }
        """.trimIndent()

        fun parse(output: String): LocalDnsConfig {
            val lines = output.lines().map { it.trim() }
            fun values(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            return LocalDnsConfig.of(values("server="), values("domain="))
        }
    }
}
