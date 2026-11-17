package org.uci.proxy.core.desktop

import java.nio.file.Path

/** Un proceso del motor en marcha. */
interface EngineProcess {
    val isAlive: Boolean

    fun destroy()
}

/** Arranca el proceso del motor con los privilegios que necesita para crear la interfaz de red. */
fun interface EngineLauncher {
    /**
     * @param port puerto local donde la ventana espera al motor.
     * @param tokenFile archivo, legible solo por el usuario, con el token que el motor debe presentar.
     */
    fun launch(port: Int, tokenFile: Path): EngineProcess
}

/** Opciones con las que se invoca el motor en la línea de órdenes. */
object EngineArguments {
    const val ENGINE = "--engine"
    const val PORT = "--port"
    const val TOKEN_FILE = "--token-file"

    /**
     * Comprobación de que el programa empaquetado arranca (Java recortada, librerías, recursos): escribe el
     * resultado en el archivo que sigue a esta opción y termina, sin abrir ventana ni tocar la red.
     */
    const val SELF_TEST = "--self-test"

    /** Solo para pruebas: segundos tras los que el motor se detiene solo. */
    const val MAX_SECONDS = "--max-seconds"

    /** Solo para pruebas: no capturar el DNS del equipo. */
    const val NO_DNS_CAPTURE = "--no-dns-capture"

    /** Solo para pruebas: capturar el DNS únicamente de estos dominios, separados por comas. */
    const val DNS_DOMAINS = "--dns-domains"

    /** Solo para pruebas: lista de CIDR separados por comas que sustituye a "todo el IPv4 público". */
    const val ROUTES = "--routes"

    /** Variable de entorno con opciones extra que la ventana añade al lanzar el motor. */
    const val EXTRA_FLAGS_ENV = "PROXYUCI_ENGINE_FLAGS"

    /** Valor que sigue a la opción [name], o null si no está. */
    fun value(args: List<String>, name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0) args.getOrNull(index + 1) else null
    }
}

/**
 * Lanza el motor como root en macOS mediante el diálogo de contraseña de administrador del sistema.
 *
 * El motor es este mismo programa, con la misma máquina virtual y la misma ruta de clases,
 * invocado con [EngineArguments.ENGINE].
 *
 * @param javaCommand ejecutable de Java con el que corre la ventana.
 * @param classPath ruta de clases de la ventana.
 * @param mainClass clase principal del programa.
 */
class MacElevatedEngineLauncher(
    private val javaCommand: String,
    private val classPath: String,
    private val mainClass: String,
    private val extraFlags: List<String>,
) : EngineLauncher {
    override fun launch(port: Int, tokenFile: Path): EngineProcess {
        val engineCommand = listOf(javaCommand, "-cp", classPath, mainClass, EngineArguments.ENGINE) +
            listOf(EngineArguments.PORT, port.toString(), EngineArguments.TOKEN_FILE, tokenFile.toString()) +
            extraFlags
        val process = ProcessBuilder(osascriptCommand(engineCommand))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        return object : EngineProcess {
            override val isAlive: Boolean get() = process.isAlive

            override fun destroy() {
                process.destroy()
            }
        }
    }

    companion object {
        private const val PROMPT = "ProxyUCI necesita permisos de administrador para crear la conexión de red."

        /**
         * `osascript` que ejecuta [command] como root, pidiendo antes la contraseña con el diálogo del sistema.
         * Cada argumento se entrecomilla para la shell y el conjunto se escapa para AppleScript.
         */
        fun osascriptCommand(command: List<String>): List<String> {
            val shellLine = command.joinToString(" ", transform = ::shellQuote)
            val script = "do shell script ${appleScriptString(shellLine)} " +
                "with prompt ${appleScriptString(PROMPT)} with administrator privileges"
            return listOf("/usr/bin/osascript", "-e", script)
        }

        /** Entre comillas simples nada se interpreta; una comilla simple se cierra, se escapa y se reabre. */
        fun shellQuote(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"

        fun appleScriptString(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Lanzador para el proceso actual. */
        fun forCurrentProcess(mainClass: String): MacElevatedEngineLauncher = MacElevatedEngineLauncher(
            javaCommand = ProcessHandle.current().info().command().orElse("java"),
            classPath = System.getProperty("java.class.path"),
            mainClass = mainClass,
            extraFlags = System.getenv(EngineArguments.EXTRA_FLAGS_ENV)?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }
}
