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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * El túnel de verdad en Windows, dentro de este proceso: adaptador Wintun real, órdenes de `netsh` reales, DNS del
 * sistema y TCP por el proxy de pruebas. Solo corre cuando [ENABLE] vale «1» (el trabajo de CI de Windows, que
 * corre como Administrador); en cualquier otro caso se omite sin hacer nada. Ver [WindowsE2E].
 */
class WindowsTunnelE2ETest {
    @Test
    fun `a real Wintun tunnel carries a download through the proxy and leaves nothing behind`() {
        if (System.getenv(ENABLE) != "1") {
            println("E2E-WINDOWS: omitida (falta $ENABLE=1)")
            return
        }
        val proxyPort = WindowsE2E.proxyPort
        val originPort = WindowsE2E.originPort
        val expectedSha = WindowsE2E.expectedSha

        // Antes de tocar la red: si los servidores de prueba no escuchan, el fallo no es del túnel.
        assertTrue(WindowsE2E.canConnect(proxyPort), "el proxy de pruebas no escucha en 127.0.0.1:$proxyPort antes de crear el túnel")
        assertTrue(WindowsE2E.canConnect(originPort), "el origen de pruebas no escucha en 127.0.0.1:$originPort antes de crear el túnel")

        // El registro de la app va a la salida estándar: sin él, un fallo del túnel no deja ninguna pista.
        val printLogger = module { single<Logger> { WindowsE2E.PrintLogger() } }
        val koin = startKoin { modules(platformModule, coreModule, tunnelModule, printLogger) }.koin
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
            println("E2E-WINDOWS: con el túnel activo, proxy alcanzable=${WindowsE2E.canConnect(proxyPort)}, origen alcanzable=${WindowsE2E.canConnect(originPort)}")

            // 1. El DNS del sistema responde con una dirección falsa del túnel: el adaptador gana al DNS real.
            val (name, address) = WindowsE2E.resolveOrDiagnose()
            assertTrue(address.hostAddress.startsWith("10."), "$name resolvió a ${address.hostAddress}, no a una dirección del túnel")

            // 2. Una descarga de 5 MB por esa dirección llega íntegra: Wintun, pila TCP, relé y proxy NTLM.
            val bytes = WindowsE2E.download("http://$name:$originPort/big.bin")
            assertEquals(WindowsE2E.BIG_SIZE, bytes.size, "tamaño de la descarga")
            assertEquals(expectedSha, WindowsE2E.sha256(bytes), "huella de la descarga")

            // 3. Al parar no queda el adaptador.
            runBlocking { session.cancelAndJoin() }
            assertFalse(WindowsE2E.adapterExists(), "el adaptador sigue existiendo tras parar el túnel")
            println("E2E-WINDOWS: ok")
        } finally {
            scope.cancel()
            stopKoin()
        }
    }

    private fun nativeDirectory(): Path =
        Path.of(System.getenv("ProgramData") ?: "C:\\ProgramData", "ProxyUCI", "native-e2e")

    private companion object {
        const val ENABLE = "PROXYUCI_E2E_WINDOWS"
    }
}
