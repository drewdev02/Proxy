package org.uci.proxy.core.platform.windows

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Versión de `wintun.dll` que se empaqueta, tal como sale del ZIP oficial de wintun.net, sin modificar. */
object WintunDll {
    const val VERSION = "0.14.1"

    /** SHA-256 de cada DLL empaquetada, por arquitectura. Se comprueba antes de cargarla. */
    val SHA256 = mapOf(
        "amd64" to "e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce",
        "arm64" to "f7ba89005544be9d85231a9e0d5f23b2d15b3311667e2dad0debd344918a3f80",
    )

    /** Arquitectura de Windows que corresponde a `os.arch` de la JVM, o null si no se empaqueta DLL para ella. */
    fun architectureFor(osArch: String): String? = when (osArch.lowercase()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }
}

/** Entrega la ruta de una `wintun.dll` lista para cargar. */
fun interface WintunDllLocator {
    /** @throws WindowsNetworkException si no hay DLL para esta máquina o no es la esperada. */
    fun locate(): Path
}

/**
 * Extrae la `wintun.dll` empaquetada como recurso y comprueba su huella antes de entregarla.
 *
 * Este código lo ejecuta el proceso elevado, que luego cargará la DLL. Por eso nunca reutiliza un archivo
 * que ya exista: cada vez crea un directorio nuevo de nombre aleatorio bajo [root], escribe ahí la DLL y
 * verifica su SHA-256, de modo que otro proceso no pueda dejar en su lugar una DLL ajena.
 *
 * @param root directorio donde crear la carpeta temporal.
 * @param openResource lee un recurso del classpath; se inyecta para poder probarlo sin recursos reales.
 */
class ResourceWintunDllLocator(
    private val root: Path,
    private val osArch: String = System.getProperty("os.arch").orEmpty(),
    private val expectedHashes: Map<String, String> = WintunDll.SHA256,
    private val openResource: (String) -> InputStream? = { ResourceWintunDllLocator::class.java.getResourceAsStream(it) },
) : WintunDllLocator {
    override fun locate(): Path {
        val architecture = WintunDll.architectureFor(osArch)
            ?: throw WindowsNetworkException("No hay wintun.dll para la arquitectura $osArch")
        val expected = expectedHashes[architecture]
            ?: throw WindowsNetworkException("No hay huella de wintun.dll para $architecture")
        val resource = "/native/windows-$architecture/wintun.dll"
        val input = openResource(resource) ?: throw WindowsNetworkException("Falta el recurso $resource")

        val directory = try {
            Files.createDirectories(root)
            Files.createTempDirectory(root, "wintun-")
        } catch (e: IOException) {
            throw WindowsNetworkException("No se pudo crear la carpeta para wintun.dll en $root: ${e.message}")
        }
        val dll = directory.resolve("wintun.dll")
        try {
            input.use { Files.copy(it, dll) }
        } catch (e: IOException) {
            throw WindowsNetworkException("No se pudo extraer wintun.dll: ${e.message}")
        }

        val actual = sha256(dll)
        if (actual != expected) {
            Files.deleteIfExists(dll)
            throw WindowsNetworkException("La wintun.dll extraída no es la esperada (SHA-256 $actual)")
        }
        // Mientras la DLL esté cargada Windows no deja borrarla; se intenta al salir.
        dll.toFile().deleteOnExit()
        directory.toFile().deleteOnExit()
        return dll
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
