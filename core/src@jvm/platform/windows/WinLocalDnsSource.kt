package org.uci.proxy.core.platform.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.uci.proxy.core.platform.CommandRunner
import org.uci.proxy.core.port.LocalDnsConfig
import org.uci.proxy.core.port.LocalDnsSource
import java.util.Base64

/**
 * El DNS del adaptador por el que sale hoy el tráfico (el de la ruta por defecto con menor métrica, sin contar el del
 * túnel), leído con PowerShell, más la lista de sufijos de búsqueda del equipo. Si esa ruta no se encuentra, vale el
 * primer adaptador con servidores DNS.
 */
class WinLocalDnsSource(private val runner: CommandRunner) : LocalDnsSource {
    override suspend fun read(): LocalDnsConfig = withContext(Dispatchers.IO) {
        val result = runner.run(COMMAND, null)
        if (result.exitCode != 0) LocalDnsConfig.NONE else parse(result.output)
    }

    internal companion object {
        private const val TUNNEL = WinPlatformTunnel.ADAPTER_NAME

        // Una línea por dato, `server=` o `domain=`: fácil de leer y de probar sin Windows.
        val SCRIPT = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            ${'$'}route = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' |
                Where-Object { ${'$'}_.InterfaceAlias -notlike '$TUNNEL*' } |
                Sort-Object { [int]${'$'}_.RouteMetric + [int]${'$'}_.InterfaceMetric } | Select-Object -First 1
            ${'$'}dns = ${'$'}null
            if (${'$'}route) {
                ${'$'}dns = Get-DnsClientServerAddress -InterfaceIndex ${'$'}route.InterfaceIndex -AddressFamily IPv4
            }
            if (-not (${'$'}dns -and ${'$'}dns.ServerAddresses)) {
                ${'$'}dns = Get-DnsClientServerAddress -AddressFamily IPv4 |
                    Where-Object { ${'$'}_.InterfaceAlias -notlike '$TUNNEL*' -and ${'$'}_.ServerAddresses } |
                    Select-Object -First 1
            }
            if (${'$'}dns) {
                foreach (${'$'}server in ${'$'}dns.ServerAddresses) { Write-Output "server=${'$'}server" }
                ${'$'}client = Get-DnsClient -InterfaceIndex ${'$'}dns.InterfaceIndex
                if (${'$'}client.ConnectionSpecificSuffix) { Write-Output "domain=${'$'}(${'$'}client.ConnectionSpecificSuffix)" }
            }
            foreach (${'$'}suffix in (Get-DnsClientGlobalSetting).SuffixSearchList) {
                if (${'$'}suffix) { Write-Output "domain=${'$'}suffix" }
            }
        """.trimIndent()

        /**
         * El guion va codificado (`-EncodedCommand`, UTF-16LE en Base64): pasado tal cual con `-Command`, las líneas y
         * las comillas se pierden por el camino entre Java, la línea de órdenes de Windows y PowerShell.
         */
        val COMMAND: List<String> = listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE)),
        )

        fun parse(output: String): LocalDnsConfig {
            val lines = output.lines().map { it.trim() }
            fun values(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            return LocalDnsConfig.of(values("server="), values("domain="))
        }
    }
}
