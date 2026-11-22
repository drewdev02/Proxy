package org.uci.proxy.core.windows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.di.tunnelModule
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.engine.TunnelStateStore
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
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
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.CountryExitStart
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * «Otro País» con Tailscale y el túnel de verdad en Windows: adaptador Wintun real, el motor Tailscale real (su librería
 * nativa, con el grafo de dependencias de la app) y una red de Tailscale de pruebas (tools/tsbridge/build.sh testnet).
 * Todo lo que el nodo abre hacia fuera pasa por un proxy NTLM de pruebas; una descarga a 203.0.113.42 entra en el
 * adaptador, viaja por Tailscale (relé DERP) hasta el nodo de salida, y este la lleva al origen de pruebas.
 *
 * Va en su propio proceso, como las demás pruebas del adaptador (Windows no deja crearlo dos veces en el mismo proceso).
 * Solo corre con PROXYUCI_E2E_WINDOWS=1.
 */
class WindowsTailscaleE2ETest {
    private val processes = mutableListOf<Process>()
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "tools/tsbridge/build.sh").exists() }

    @Test
    fun `a real Wintun tunnel goes out through a Tailscale exit node reached only through an NTLM proxy`() {
        if (System.getenv(ENABLE) != "1") {
            println("E2E-WINDOWS: omitida (falta $ENABLE=1)")
            return
        }
        val originPort = WindowsE2E.originPort
        val expectedSha = WindowsE2E.expectedSha
        assertTrue(WindowsE2E.canConnect(originPort), "el origen de pruebas no escucha en 127.0.0.1:$originPort")

        val printLogger = module { single<Logger> { WindowsE2E.PrintLogger() } }
        val koin = startKoin { modules(platformModule, coreModule, tunnelModule, printLogger) }.koin
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engines = koin.get<CountryEngines>()
            assertNotNull(engines[CountryEngineKind.TAILSCALE], "la librería de Tailscale no cargó (tools/tsbridge/build.sh)")
            val net = startTestNet("127.0.0.1:$originPort")
            val proxyPort = startProxy()

            val dispatchers = koin.get<AppDispatchers>()
            val stateStore = koin.get<TunnelStateStore>()
            val runner = TunnelRunner(
                koin.get(), koin.get(), koin.get(), koin.get(), stateStore, dispatchers, koin.get<Logger>(),
                // Solo el rango de documentación entra en el túnel (además de la red de Tailscale): la conexión del
                // ejecutor con GitHub no se toca.
                publicRoutes = listOf(Ipv4Route.parse("203.0.113.0/24")),
                engines = engines,
            )
            val tunnel = WinPlatformTunnel(
                network = WinNetwork(ProcessCommandRunner()),
                wintunOpener = JnaWintunOpener(ResourceWintunDllLocator(nativeDirectory())),
                ioDispatcher = dispatchers.background,
                logger = koin.get(),
            )
            val exit = CountryExit.Tailscale(controlUrl = net.controlUrl, exitNode = net.exitNode, hostname = "windows-e2e")
            val profile = ProxyProfile("ts", "Tailscale E2E", "127.0.0.1", proxyPort, ProxyProtocol.HTTP, "andrey", exit)
            val start = CountryExitStart(exit, CountryExitSecrets.Tailscale(net.authKey), Files.createTempDirectory("ts-e2e").toString())

            val session = scope.launch { runner.run(tunnel, profile, "secreta7", vpn = start) }
            val state = runBlocking {
                withTimeout(120.seconds) { stateStore.state.first { it is TunnelState.Connected || it is TunnelState.Failed } }
            }
            assertEquals(TunnelState.Connected("ts"), state, "el túnel por Tailscale no conectó")

            // Windows tarda unos segundos en dejar lista la dirección y las rutas del adaptador recién creado.
            var route = ""
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                route = WindowsE2E.routeInterface("203.0.113.42")
                if (route == WinPlatformTunnel.ADAPTER_NAME) break
                Thread.sleep(1_000)
            }
            println("E2E-WINDOWS: 203.0.113.42 por «$route»")
            assertEquals(WinPlatformTunnel.ADAPTER_NAME, route)

            val body = WindowsE2E.download("http://203.0.113.42:$originPort/big.bin")
            assertEquals(WindowsE2E.BIG_SIZE, body.size, "tamaño de la descarga por Tailscale")
            assertEquals(expectedSha, WindowsE2E.sha256(body), "descarga íntegra por Tailscale")

            runBlocking { session.cancelAndJoin() }
            assertFalse(WindowsE2E.adapterExists(), "el adaptador sigue existiendo tras parar el túnel")
            println("E2E-WINDOWS: Tailscale ok")
        } finally {
            scope.cancel()
            stopKoin()
            processes.forEach { it.destroy() }
        }
    }

    private class TestNet(val controlUrl: String, val authKey: String, val exitNode: String)

    private fun startTestNet(redirect: String): TestNet {
        val exe = root?.let { r -> listOf("testnet.exe", "testnet").map { File(r, "build/tsbridge/$it") }.firstOrNull { it.exists() } }
            ?: fail("no está la red de pruebas (tools/tsbridge/build.sh testnet)")
        val process = ProcessBuilder(exe.path, "-redirect", redirect).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        processes += process
        val line = process.inputStream.bufferedReader().readLine() ?: fail("la red de pruebas no arrancó")
        fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: fail("falta $name en $line")
        return TestNet(field("controlUrl"), field("authKey"), field("exitNode"))
    }

    /** Un proxy NTLM propio (sin --redirect, que mandaría también el control de Tailscale al origen). */
    private fun startProxy(): Int {
        val port = ServerSocket(0).use { it.localPort }
        val script = File(root, "tools/test_proxy.py")
        val process = ProcessBuilder("python", "-u", script.path, "--auth", "ntlm", "--port", "$port", "--upstream")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
        processes += process
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return port
            Thread.sleep(100)
        }
        fail("el proxy de pruebas no escucha en $port")
    }

    private fun nativeDirectory(): Path =
        Path.of(System.getenv("ProgramData") ?: "C:\\ProgramData", "ProxyUCI", "native-e2e")

    private companion object {
        const val ENABLE = "PROXYUCI_E2E_WINDOWS"
    }
}
