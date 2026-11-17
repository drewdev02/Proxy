package org.uci.proxy.core.windows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.di.tunnelModule
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.platform.windows.JnaWintunOpener
import org.uci.proxy.core.platform.windows.ResourceWintunDllLocator
import org.uci.proxy.core.platform.windows.WinNetwork
import org.uci.proxy.core.platform.windows.WinPlatformTunnel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.profile.AppDispatchers
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * El túnel de verdad en Windows: adaptador Wintun real, órdenes de `netsh` reales, DNS del sistema y TCP por el
 * proxy de pruebas. Solo corre cuando [ENABLE] vale «1» (el trabajo de CI de Windows, que corre como
 * Administrador); en cualquier otro caso se omite sin hacer nada.
 *
 * Fuera del código, el trabajo de CI tiene que haber arrancado:
 * - `tools/test_proxy.py --auth ntlm --upstream --redirect 127.0.0.1:<origen>` en el puerto del proxy, y
 * - un servidor HTTP en el puerto de origen que sirva `big.bin`, cuya huella va en [BIG_SHA].
 *
 * Las rutas que entran en el túnel se limitan a 192.0.2.0/24 (TEST-NET-1, reservada para documentación):
 * el resto del tráfico del ejecutor, incluida su conexión con GitHub, no se toca.
 */
class WindowsTunnelE2ETest {
    @Test
    fun `a real Wintun tunnel carries a download through the proxy and leaves nothing behind`() {
        if (System.getenv(ENABLE) != "1") {
            println("E2E-WINDOWS: omitida (falta $ENABLE=1)")
            return
        }
        val proxyPort = System.getenv(PROXY_PORT)?.toInt() ?: 3128
        val originPort = System.getenv(ORIGIN_PORT)?.toInt() ?: 8000
        val expectedSha = requireNotNull(System.getenv(BIG_SHA)) { "Falta $BIG_SHA" }

        val koin = startKoin { modules(platformModule, coreModule, tunnelModule) }.koin
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dispatchers = koin.get<AppDispatchers>()
            val stateStore = koin.get<TunnelStateStore>()
            val runner = TunnelRunner(
                koin.get(), koin.get(), koin.get(), koin.get(), stateStore, dispatchers, koin.get<Logger>(),
                publicRoutes = listOf(Ipv4Route.parse("192.0.2.0/24")),
            )
            val tunnel = WinPlatformTunnel(
                network = WinNetwork(ProcessCommandRunner()),
                wintunOpener = JnaWintunOpener(ResourceWintunDllLocator(nativeDirectory())),
                ioDispatcher = dispatchers.background,
                logger = koin.get(),
            )
            val profile = ProxyProfile("e2e", "E2E", "127.0.0.1", proxyPort, ProxyProtocol.HTTP, "andrey")

            val session = scope.launch { runner.run(tunnel, profile, "secreta7") }
            val state = runBlocking {
                withTimeout(90.seconds) { stateStore.state.first { it is TunnelState.Connected || it is TunnelState.Failed } }
            }
            assertTrue(state is TunnelState.Connected, "el túnel no conectó: $state")

            // 1. El DNS del sistema responde con una dirección falsa del túnel: el adaptador gana al DNS real.
            val name = "e2e-${System.nanoTime()}.uci.test"
            val address = resolveOrDiagnose(name)
            assertTrue(address.hostAddress.startsWith("10."), "$name resolvió a ${address.hostAddress}, no a una dirección del túnel")

            // 2. Una descarga de 5 MB por esa dirección llega íntegra: Wintun, pila TCP, relé y proxy NTLM.
            val bytes = download("http://$name:$originPort/big.bin")
            assertEquals(5 * 1024 * 1024, bytes.size, "tamaño de la descarga")
            assertEquals(expectedSha, sha256(bytes), "huella de la descarga")

            // 3. Al parar no queda el adaptador.
            runBlocking { session.cancelAndJoin() }
            assertFalse(adapterExists(), "el adaptador sigue existiendo tras parar el túnel")
            println("E2E-WINDOWS: ok")
        } finally {
            scope.cancel()
            stopKoin()
        }
    }

    /**
     * Resuelve [name] con el DNS del sistema, reintentando unos segundos: Windows puede tardar un instante en
     * empezar a usar el servidor DNS de un adaptador recién configurado. Si no resuelve, vuelca el estado de la
     * red **mientras el túnel sigue activo** (después de limpiar ya no habría nada que ver) y consulta al servidor
     * DNS del túnel directamente, para distinguir «el túnel no responde» de «Windows no lo consulta».
     */
    private fun resolveOrDiagnose(name: String): InetAddress {
        var last: Exception? = null
        repeat(10) {
            try {
                return InetAddress.getByName(name)
            } catch (e: java.net.UnknownHostException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        val runner = ProcessCommandRunner()
        fun show(title: String, vararg command: String) =
            println("E2E-DIAG [$title]\n" + runner.run(command.toList(), null).output.trim())
        show("rutas", "route", "print", "-4")
        show("interfaces", "netsh", "interface", "ipv4", "show", "interfaces")
        show("DNS por interfaz", "netsh", "interface", "ipv4", "show", "dnsservers")
        show("configuración", "ipconfig", "/all")
        val dnsServer = runner.run(listOf("netsh", "interface", "ipv4", "show", "dnsservers", "name=${WinPlatformTunnel.ADAPTER_NAME}"), null)
            .output.lineSequence().mapNotNull { Regex("""(\d+\.\d+\.\d+\.\d+)""").find(it)?.value }.firstOrNull()
        println("E2E-DIAG servidor DNS del adaptador: $dnsServer")
        if (dnsServer != null) show("nslookup directo al DNS del túnel", "nslookup", name, dnsServer)
        show("Resolve-DnsName", "powershell", "-NoProfile", "-Command", "Resolve-DnsName $name -Type A -ErrorAction Continue | Out-String")
        throw AssertionError("Windows no resolvió $name por el DNS del túnel: $last")
    }

    private fun nativeDirectory(): Path =
        Path.of(System.getenv("ProgramData") ?: "C:\\ProgramData", "ProxyUCI", "native-e2e")

    private fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        return connection.getInputStream().use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Windows tarda un instante en retirar el adaptador: se espera un poco antes de darlo por presente. */
    private fun adapterExists(): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            repeat(20) {
                if (!listing().contains(WinPlatformTunnel.ADAPTER_NAME)) return@withContext false
                delay(500)
            }
            true
        }
    }

    private fun listing(): String =
        ProcessCommandRunner().run(listOf("netsh", "interface", "ipv4", "show", "interfaces"), null).output

    private companion object {
        const val ENABLE = "PROXYUCI_E2E_WINDOWS"
        const val PROXY_PORT = "PROXYUCI_E2E_PROXY_PORT"
        const val ORIGIN_PORT = "PROXYUCI_E2E_ORIGIN_PORT"
        const val BIG_SHA = "PROXYUCI_E2E_BIG_SHA"
    }
}
