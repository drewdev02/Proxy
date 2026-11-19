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

    /**
     * `security -i` no acepta órdenes de más de unos 4 KB, y un archivo `.ovpn` en Base64 pasa de eso. Un valor largo se
     * guarda en trozos (`clave.part1`, `clave.part2`…) y la clave principal solo dice cuántos hay.
     */
    override fun put(key: String, value: String) {
        requireSafe(key)
        val encoded = Base64.encode(value.encodeToByteArray())
        val previousParts = partCount(key)
        if (encoded.length <= MAX_CHUNK) {
            write(key, encoded)
        } else {
            val chunks = encoded.chunked(MAX_CHUNK)
            chunks.forEachIndexed { index, chunk -> write(partKey(key, index + 1), chunk) }
            // La marca, al final: si algo falla antes, la clave sigue con su valor anterior.
            write(key, "$CHUNKS_MARKER${chunks.size}")
            removeParts(key, from = chunks.size + 1, to = previousParts)
            return
        }
        removeParts(key, from = 1, to = previousParts)
    }

    override fun get(key: String): String? {
        requireSafe(key)
        val stored = read(key) ?: return null
        val count = chunkCountOf(stored)
        val encoded = if (count == null) {
            stored
        } else {
            buildString { for (index in 1..count) append(read(partKey(key, index)) ?: return null) }
        }
        return try {
            Base64.decode(encoded).decodeToString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun remove(key: String) {
        requireSafe(key)
        removeParts(key, from = 1, to = partCount(key))
        delete(key)
    }

    private fun write(account: String, data: String) {
        val result = run(listOf(SECURITY, "-i"), stdin = "add-generic-password -U -a \"$account\" -s \"$service\" -w $data\n")
        check(result.exitCode == 0) { "El llavero rechazó guardar '$account' (código ${result.exitCode})" }
    }

    private fun read(account: String): String? {
        val result = run(listOf(SECURITY, "find-generic-password", "-a", account, "-s", service, "-w"))
        return when (result.exitCode) {
            0 -> result.output.trim()
            ITEM_NOT_FOUND -> null
            else -> error("El llavero no pudo leer '$account' (código ${result.exitCode})")
        }
    }

    private fun delete(account: String) {
        val result = run(listOf(SECURITY, "delete-generic-password", "-a", account, "-s", service))
        check(result.exitCode == 0 || result.exitCode == ITEM_NOT_FOUND) {
            "El llavero no pudo borrar '$account' (código ${result.exitCode})"
        }
    }

    /** Cuántos trozos tiene guardados [key] ahora (0 si es un valor de una pieza o no existe). */
    private fun partCount(key: String): Int = read(key)?.let(::chunkCountOf) ?: 0

    private fun chunkCountOf(stored: String): Int? = if (stored.startsWith(CHUNKS_MARKER)) stored.removePrefix(CHUNKS_MARKER).toIntOrNull() else null

    private fun removeParts(key: String, from: Int, to: Int) {
        for (index in from..to) delete(partKey(key, index))
    }

    private fun partKey(key: String, index: Int) = "$key.part$index"

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

        // Con margen frente al límite de unos 4 KB por orden de `security -i`.
        const val MAX_CHUNK = 3000

        // No es Base64 válido (lleva «:»), así que no se confunde con un valor de una pieza.
        const val CHUNKS_MARKER = "chunks:"
    }
}
