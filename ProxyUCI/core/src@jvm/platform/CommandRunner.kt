package org.uci.proxy.core.platform

import java.util.concurrent.TimeUnit

class CommandResult(val exitCode: Int, val output: String)

/** Ejecuta una orden del sistema. Existe como puerto para poder probar la configuración de red sin tocarla. */
fun interface CommandRunner {
    /** @param input texto que se entrega a la orden por su entrada estándar, o null si no lleva. */
    fun run(command: List<String>, input: String?): CommandResult
}

class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, input: String?): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.use { if (input != null) it.write(input.encodeToByteArray()) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(exitCode = -1, output = "tiempo agotado")
        }
        return CommandResult(process.exitValue(), output)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
