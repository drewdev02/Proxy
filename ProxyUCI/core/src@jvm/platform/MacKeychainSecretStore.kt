package org.uci.proxy.core.platform

import org.uci.proxy.core.port.SecretStore
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64

/**
 * Guarda los secretos en el llavero de macOS mediante la herramienta `security` del sistema.
 *
 * El valor se guarda en Base64: así es siempre ASCII, `security` lo devuelve tal cual y no hace
 * falta entrecomillarlo. Al escribir, la orden viaja por la entrada estándar (`security -i`) y no
 * como argumento, para que la contraseña no sea visible en la lista de procesos.
 */
class MacKeychainSecretStore(private val service: String) : SecretStore {
    init {
        requireSafe(service)
    }

    override fun put(key: String, value: String) {
        requireSafe(key)
        val encoded = Base64.encode(value.encodeToByteArray())
        val result = run(listOf(SECURITY, "-i"), stdin = "add-generic-password -U -a \"$key\" -s \"$service\" -w $encoded\n")
        check(result.exitCode == 0) { "El llavero rechazó guardar '$key' (código ${result.exitCode})" }
    }

    override fun get(key: String): String? {
        requireSafe(key)
        val result = run(listOf(SECURITY, "find-generic-password", "-a", key, "-s", service, "-w"))
        return when (result.exitCode) {
            0 -> try {
                Base64.decode(result.output.trim()).decodeToString()
            } catch (_: IllegalArgumentException) {
                null
            }
            ITEM_NOT_FOUND -> null
            else -> error("El llavero no pudo leer '$key' (código ${result.exitCode})")
        }
    }

    override fun remove(key: String) {
        requireSafe(key)
        val result = run(listOf(SECURITY, "delete-generic-password", "-a", key, "-s", service))
        check(result.exitCode == 0 || result.exitCode == ITEM_NOT_FOUND) {
            "El llavero no pudo borrar '$key' (código ${result.exitCode})"
        }
    }

    private class Result(val exitCode: Int, val output: String)

    private fun run(command: List<String>, stdin: String? = null): Result {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { if (stdin != null) it.write(stdin.encodeToByteArray()) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("El llavero no respondió en $TIMEOUT_SECONDS s")
        }
        return Result(process.exitValue(), output)
    }

    // Las claves y el servicio se intercalan en una orden: se limitan a caracteres que no necesitan escape.
    private fun requireSafe(text: String) {
        require(text.isNotEmpty() && text.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" }) {
            "Identificador no válido para el llavero: '$text'"
        }
    }

    private companion object {
        const val SECURITY = "/usr/bin/security"
        const val ITEM_NOT_FOUND = 44
        const val TIMEOUT_SECONDS = 10L
    }
}
