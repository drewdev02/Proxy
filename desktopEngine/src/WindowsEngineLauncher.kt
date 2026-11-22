package org.uci.proxy.desktop

import java.nio.file.Path

/**
 * Lanza el motor como Administrador en Windows mediante el aviso de control de cuentas (UAC).
 *
 * El motor es este mismo programa invocado con [EngineArguments.ENGINE]. La ventana sigue corriendo sin
 * privilegios: solo se eleva el motor.
 *
 * Windows no permite a un proceso normal vigilar ni terminar a uno elevado, así que se lanza PowerShell,
 * que pide la elevación y espera a que el motor acabe: mientras PowerShell vive, el motor vive. Si el usuario
 * rechaza el aviso de UAC, PowerShell termina enseguida y la ventana lo ve como un motor que no arrancó.
 *
 * @param programCommand cómo se invoca este programa, sin las opciones del motor: el ejecutable empaquetado,
 *   o `java -cp <ruta> <clase principal>` al ejecutar desde el entorno de desarrollo.
 */
class WindowsElevatedEngineLauncher(
    private val programCommand: List<String>,
    private val extraFlags: List<String>,
) : EngineLauncher {
    override fun launch(port: Int, tokenFile: Path): EngineProcess {
        val engineCommand = programCommand + EngineArguments.ENGINE +
            listOf(EngineArguments.PORT, port.toString(), EngineArguments.TOKEN_FILE, tokenFile.toString()) +
            extraFlags
        val process = ProcessBuilder(powershellCommand(engineCommand))
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
        /**
         * Propiedad del sistema con la ruta del ejecutable empaquetado. La define el arranque del `.exe`;
         * sin ella se está ejecutando desde el entorno de desarrollo.
         */
        const val LAUNCHER_PROPERTY = "proxyuci.launcher"

        /**
         * Orden de PowerShell que ejecuta [command] elevado y oculto, y espera a que termine.
         * El primer elemento es el ejecutable y el resto sus argumentos, cada uno entre comillas dobles
         * para que Windows no los parta por los espacios (la ruta de clases puede tenerlos).
         */
        fun powershellCommand(command: List<String>): List<String> {
            val file = psString(command.first())
            val arguments = command.drop(1).joinToString(",") { psString("\"" + it + "\"") }
            val script = "\$p = Start-Process -FilePath $file -ArgumentList @($arguments) -Verb RunAs -WindowStyle Hidden -PassThru; " +
                "\$p.WaitForExit(); exit \$p.ExitCode"
            return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
        }

        /** Entre comillas simples PowerShell no interpreta nada; una comilla simple se duplica. */
        fun psString(text: String): String = "'" + text.replace("'", "''") + "'"

        /** Lanzador para el proceso actual. */
        fun forCurrentProcess(mainClass: String): WindowsElevatedEngineLauncher {
            val packaged = System.getProperty(LAUNCHER_PROPERTY)
            val program = if (packaged != null) {
                listOf(packaged)
            } else {
                listOf(
                    ProcessHandle.current().info().command().orElse("java"),
                    "-cp",
                    System.getProperty("java.class.path"),
                    mainClass,
                )
            }
            return WindowsElevatedEngineLauncher(
                programCommand = program,
                extraFlags = System.getenv(EngineArguments.EXTRA_FLAGS_ENV)?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            )
        }
    }
}
