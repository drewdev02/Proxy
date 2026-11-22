package org.uci.proxy.desktop

import org.uci.proxy.core.windows.WindowsE2E
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.port.NameServiceReporter
import org.uci.proxy.core.data.StoredSettingsRepository
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.platform.JavaSecureRandomBytes
import org.uci.proxy.core.port.TunnelState
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.data.StoredProfileRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * El `ProxyUCI.exe` empaquetado como motor del túnel: el controlador de la ventana (el de la app) lanza el `.exe`
 * con `--engine`, este extrae su Java recortada, crea el adaptador Wintun real y atiende la conexión por el socket
 * local. Prueba la cadena que usará el usuario, menos el aviso de UAC: el ejecutor de CI ya es Administrador, así
 * que el motor se lanza directamente, sin PowerShell.
 *
 * Solo corre cuando [EXE_VAR] apunta al `.exe` (ver [WindowsE2E] para los servidores de prueba).
 */
class WindowsPackagedExeE2ETest {
    @Test
    fun `the packaged exe runs the engine and carries a download through the proxy`() {
        val exeVar = System.getenv(EXE_VAR)
        if (exeVar == null) {
            println("E2E-EXE: omitida (falta $EXE_VAR)")
            return
        }
        val exe = Path.of(exeVar)
        assertTrue(Files.exists(exe), "no existe el .exe $exe")
        assertTrue(WindowsE2E.canConnect(WindowsE2E.proxyPort), "el proxy de pruebas no escucha antes de empezar")
        assertTrue(WindowsE2E.canConnect(WindowsE2E.originPort), "el origen de pruebas no escucha antes de empezar")

        val dispatchers = AppDispatchers(background = Dispatchers.IO)
        val logger = WindowsE2E.PrintLogger()
        val repository = StoredProfileRepository(InMemoryKeyValueStore(), InMemorySecretStore(), dispatchers, logger)
        val profile = ProxyProfile("e2e", "E2E", "127.0.0.1", WindowsE2E.proxyPort, ProxyProtocol.HTTP, "andrey")
        val engineLog = Files.createTempFile("proxyuci-motor-", ".log")

        // Lo que haría la app, salvo el UAC: el motor es el propio .exe, con las rutas acotadas a 192.0.2.0/24.
        val launcher = EngineLauncher { port, tokenFile ->
            val process = ProcessBuilder(
                exe.toString(), EngineArguments.ENGINE, EngineArguments.PORT, port.toString(),
                EngineArguments.TOKEN_FILE, tokenFile.toString(), EngineArguments.ROUTES, "192.0.2.0/24",
                "--log-file", engineLog.toString(),
            ).start()
            object : EngineProcess {
                override val isAlive: Boolean get() = process.isAlive

                override fun destroy() {
                    process.destroy()
                }
            }
        }
        val controller = DesktopTunnelController(
            profiles = repository,
            launcher = launcher,
            randomBytes = JavaSecureRandomBytes(),
            dispatchers = dispatchers,
            logger = logger,
            logBuffer = LogBuffer(),
            settings = StoredSettingsRepository(InMemoryKeyValueStore()),
            nameStatus = NameServiceReporter.None,
            launchTimeout = 120.seconds,
        )

        try {
            runBlocking {
                repository.save(profile, "secreta7")
                controller.connect(profile.id)
                val state = withTimeout(150.seconds) { controller.state.first { it is TunnelState.Connected || it is TunnelState.Failed } }
                assertTrue(state is TunnelState.Connected, "el motor empaquetado no conectó: $state")

                val (name, address) = WindowsE2E.resolveOrDiagnose()
                assertTrue(address.hostAddress.startsWith("10."), "$name resolvió a ${address.hostAddress}, no a una dirección del túnel")

                val bytes = WindowsE2E.download("http://$name:${WindowsE2E.originPort}/big.bin")
                assertEquals(WindowsE2E.BIG_SIZE, bytes.size, "tamaño de la descarga")
                assertEquals(WindowsE2E.expectedSha, WindowsE2E.sha256(bytes), "huella de la descarga")

                controller.disconnect()
                withTimeout(30.seconds) { controller.state.first { it == TunnelState.Disconnected } }
                assertFalse(WindowsE2E.adapterExists(), "el adaptador sigue existiendo tras desconectar")
            }
            println("E2E-EXE: ok")
        } finally {
            // El registro del motor es lo único que explica un fallo dentro de un proceso aparte.
            println("E2E-EXE: registro del motor\n" + Files.readString(engineLog))
        }
    }

    private companion object {
        const val EXE_VAR = "PROXYUCI_E2E_EXE"
    }
}
