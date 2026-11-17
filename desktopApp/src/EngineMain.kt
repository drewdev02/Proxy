package org.uci.proxy

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.uci.proxy.core.desktop.EngineArguments
import org.uci.proxy.core.desktop.EngineSession
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.di.tunnelModule
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.platform.macos.JnaUtunOpener
import org.uci.proxy.core.platform.macos.MacNetwork
import org.uci.proxy.core.platform.macos.MacPlatformTunnel
import org.uci.proxy.core.platform.windows.JnaWintunOpener
import org.uci.proxy.core.platform.windows.ResourceWintunDllLocator
import org.uci.proxy.core.platform.windows.WinNetwork
import org.uci.proxy.core.platform.windows.WinPlatformTunnel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.profile.AppDispatchers
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

private const val LOG_FILE = "--log-file"

/**
 * Proceso del motor en macOS. Lo lanza la ventana con privilegios de administrador; se conecta a
 * ella por un socket local, recibe el perfil y mantiene el túnel hasta que se le pide parar.
 * Raíz de composición de este proceso: sin interfaz de usuario y con los módulos del túnel.
 */
fun runEngine(args: List<String>) {
    // Antes de que arranque el registro: el motor no tiene consola, así que para depurar se manda a un archivo.
    EngineArguments.value(args, LOG_FILE)?.let { path ->
        val stream = PrintStream(FileOutputStream(path, true), true)
        System.setOut(stream)
        System.setErr(stream)
    }

    val port = requireNotNull(EngineArguments.value(args, EngineArguments.PORT)?.toIntOrNull()) { "Falta ${EngineArguments.PORT}" }
    val tokenFile = requireNotNull(EngineArguments.value(args, EngineArguments.TOKEN_FILE)) { "Falta ${EngineArguments.TOKEN_FILE}" }
    val token = Files.readString(Path(tokenFile)).trim()

    val koin = startKoin { modules(platformModule, coreModule, tunnelModule) }.koin
    val dispatchers = koin.get<AppDispatchers>()
    val logger = koin.get<Logger>()

    // Opciones de prueba: acotan lo que el túnel toca en el equipo.
    val testRoutes = EngineArguments.value(args, EngineArguments.ROUTES)?.split(',')?.map(Ipv4Route::parse)
    val runner = if (testRoutes == null) {
        koin.get<TunnelRunner>()
    } else {
        TunnelRunner(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), dispatchers, logger, publicRoutes = testRoutes)
    }
    val tunnel = when (val kind = koin.get<PlatformInfo>().kind) {
        PlatformKind.MACOS -> MacPlatformTunnel(
            network = MacNetwork(ProcessCommandRunner()),
            utunOpener = JnaUtunOpener(),
            ioDispatcher = dispatchers.background,
            logger = logger,
            dnsDomains = when {
                EngineArguments.NO_DNS_CAPTURE in args -> emptyList()
                else -> EngineArguments.value(args, EngineArguments.DNS_DOMAINS)?.split(',')
                    ?: MacPlatformTunnel.ALL_DOMAINS
            },
        )
        PlatformKind.WINDOWS -> WinPlatformTunnel(
            network = WinNetwork(ProcessCommandRunner()),
            wintunOpener = JnaWintunOpener(ResourceWintunDllLocator(windowsNativeDirectory())),
            ioDispatcher = dispatchers.background,
            logger = logger,
        )
        else -> error("El motor no está disponible en ${kind.displayName}")
    }
    val session = EngineSession(
        runner = runner,
        stateStore = koin.get(),
        platformTunnel = tunnel,
        ioDispatcher = dispatchers.background,
        logger = logger,
        maxDuration = EngineArguments.value(args, EngineArguments.MAX_SECONDS)?.toIntOrNull()?.seconds,
    )

    runBlocking {
        Socket(InetAddress.getLoopbackAddress(), port).use { session.serve(it, token) }
    }
}

/**
 * Carpeta donde el motor elevado extrae `wintun.dll`. Está bajo `ProgramData` y no bajo el perfil del
 * usuario: el motor carga esa DLL con privilegios de Administrador, así que no debe salir de un
 * lugar donde un proceso sin privilegios pueda sustituirla.
 */
private fun windowsNativeDirectory() =
    Path(System.getenv("ProgramData") ?: "C:\\ProgramData").resolve("ProxyUCI").resolve("native")
