package org.uci.proxy

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.uci.proxy.core.port.NameServiceReporter
import org.uci.proxy.core.desktop.EngineNameForwarder
import org.koin.dsl.module
import org.uci.proxy.core.desktop.EngineArguments
import org.uci.proxy.core.desktop.EngineSession
import org.uci.proxy.core.traffic.TrafficStore
import org.uci.proxy.core.desktop.MacElevatedEngineLauncher
import org.uci.proxy.core.desktop.WindowsElevatedEngineLauncher
import org.uci.proxy.core.di.coreModule
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.di.tunnelModule
import org.uci.proxy.core.engine.TunnelRunner
import org.uci.proxy.core.packet.Ipv4Route
import org.uci.proxy.core.platform.JnaProbe
import org.uci.proxy.core.vpn.tailscale.NativeTailscaleBridge
import org.uci.proxy.core.vpn.tailscale.TailscaleBridge
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.port.LocalDnsSource
import org.uci.proxy.core.platform.macos.JnaUtunOpener
import org.uci.proxy.core.platform.macos.MacNetwork
import org.uci.proxy.core.platform.macos.MacPlatformTunnel
import org.uci.proxy.core.platform.windows.JnaWintunOpener
import org.uci.proxy.core.platform.windows.ResourceWintunDllLocator
import org.uci.proxy.core.platform.windows.WindowsProtectedDirectory
import org.uci.proxy.core.platform.windows.WinNetwork
import org.uci.proxy.core.platform.windows.WinPlatformTunnel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import org.uci.proxy.core.common.AppDispatchers
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.system.exitProcess
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

    // El motor corre aparte de la ventana: lo que cuente de los servicios de nombres se reenvía por el socket.
    val nameForwarder = EngineNameForwarder()
    val koin = startKoin {
        modules(
            platformModule, coreModule, tunnelModule,
            module {
                single<NameServiceReporter> { nameForwarder }
                // El motor corre con privilegios: en Windows la librería de Tailscale se extrae, como wintun.dll, bajo
                // ProgramData y no en la carpeta temporal del usuario.
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                    single<TailscaleBridge> {
                        val (dir, file) = NativeTailscaleBridge.desktopResourceDir() ?: return@single TailscaleBridge.Unavailable
                        NativeTailscaleBridge.fromResource(dir, file, root = windowsNativeDirectory(), prepareRoot = WindowsProtectedDirectory::prepare)
                    }
                }
            },
        )
    }.koin
    val dispatchers = koin.get<AppDispatchers>()
    val logger = koin.get<Logger>()

    // Opciones de prueba: acotan lo que el túnel toca en el equipo.
    val testRoutes = EngineArguments.value(args, EngineArguments.ROUTES)?.split(',')?.map(Ipv4Route::parse)
    val runner = if (testRoutes == null) {
        koin.get<TunnelRunner>()
    } else {
        TunnelRunner(
            koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), dispatchers, logger,
            publicRoutes = testRoutes,
            engines = koin.get(),
            localDnsSource = koin.getOrNull() ?: LocalDnsSource.None,
            dnsTransport = koin.get(),
            proxyProbe = koin.get(),
            localAddresses = koin.get(),
        )
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
            wintunOpener = JnaWintunOpener(ResourceWintunDllLocator(windowsNativeDirectory(), prepareRoot = WindowsProtectedDirectory::prepare)),
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
        logBuffer = koin.get(),
        maxDuration = EngineArguments.value(args, EngineArguments.MAX_SECONDS)?.toIntOrNull()?.seconds,
        nameForwarder = nameForwarder,
        traffic = koin.get<TrafficStore>(),
    )

    runBlocking {
        Socket(InetAddress.getLoopbackAddress(), port).use { session.serve(it, token) }
    }
}

/**
 * Comprobación del programa empaquetado: arranca el mismo grafo de dependencias que el motor, carga la librería
 * nativa de JNA y, en Windows, extrae y verifica `wintun.dll`. Con una Java recortada, un módulo que falte
 * aparece aquí y no cuando el usuario pulsa «Conectar». Escribe `OK` (o `FALLO` con el error) en [reportFile].
 */
fun runSelfTest(reportFile: String) {
    val report = StringBuilder()
    try {
        val koin = startKoin { modules(platformModule, coreModule, tunnelModule) }.koin
        koin.get<TunnelRunner>()
        val platform = koin.get<PlatformInfo>()
        report.appendLine("plataforma=${platform.kind}")
        report.appendLine("java=${System.getProperty("java.version")} ${System.getProperty("java.vm.name")}")
        report.appendLine("arranque=${System.getProperty(WindowsElevatedEngineLauncher.LAUNCHER_PROPERTY)}")
        report.appendLine("lanzador-jpackage=${System.getProperty(MacElevatedEngineLauncher.JPACKAGE_APP_PATH_PROPERTY)}")
        // Con `./kotlin run`, este es el classpath exacto que Amper usa para ejecutar la app: tools/windows-exe lo
        // aprovecha para empaquetar las mismas librerías (el jar ejecutable de Amper pierde las que se llaman igual).
        report.appendLine("classpath=${System.getProperty("java.class.path")}")
        // Cargar la parte nativa de JNA: falla si no se pudo extraer o si falta algún módulo.
        report.appendLine("jna=${JnaProbe.pointerSize()} bytes por puntero")
        if (platform.kind == PlatformKind.WINDOWS) {
            val dll = ResourceWintunDllLocator(Files.createTempDirectory("proxyuci-autoprueba")).locate()
            report.appendLine("wintun=${dll.fileName} (${Files.size(dll)} bytes, huella verificada)")
        }
        // Tailscale: si la app trae su librería, tiene que cargar (un módulo de Java que falte se vería aquí).
        val tailscale = NativeTailscaleBridge.desktopResourceDir()?.let { (dir, file) ->
            if (NativeTailscaleBridge::class.java.getResource("/native/tsbridge/$dir/$file") == null) {
                "no incluido"
            } else {
                val bridge = NativeTailscaleBridge.fromResource(dir, file, root = Files.createTempDirectory("proxyuci-autoprueba"))
                check(bridge.isAvailable) { "la librería de Tailscale está en la app pero no carga" }
                "cargado"
            }
        } ?: "sin librería para este sistema"
        report.appendLine("tailscale=$tailscale")
        Files.writeString(Path(reportFile), "OK\n$report")
    } catch (error: Throwable) {
        Files.writeString(Path(reportFile), "FALLO\n$report\n${error.stackTraceToString()}")
        exitProcess(1)
    }
}

/**
 * Carpeta donde el motor elevado extrae `wintun.dll`. Está bajo `ProgramData` y no bajo el perfil del
 * usuario: el motor carga esa DLL con privilegios de Administrador, así que no debe salir de un
 * lugar donde un proceso sin privilegios pueda sustituirla. Antes de usarla se protege con
 * [WindowsProtectedDirectory]: un usuario normal puede crear carpetas en `ProgramData`.
 */
private fun windowsNativeDirectory() =
    Path(System.getenv("ProgramData") ?: "C:\\ProgramData").resolve("ProxyUCI").resolve("native")
