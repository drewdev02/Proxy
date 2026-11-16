package org.uci.proxy.core.platform

import org.uci.proxy.core.port.KeyValueStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/** Guarda pares clave-valor en un archivo `.properties`. Cada escritura reemplaza el archivo de forma atómica. */
class FileKeyValueStore(private val file: Path) : KeyValueStore {
    private val lock = Any()
    private val properties = Properties().apply {
        if (file.exists()) file.inputStream().use(::load)
    }

    override fun getString(key: String): String? = synchronized(lock) { properties.getProperty(key) }

    override fun putString(key: String, value: String) = synchronized(lock) {
        properties.setProperty(key, value)
        flush()
    }

    override fun remove(key: String) = synchronized(lock) {
        if (properties.remove(key) != null) flush()
    }

    private fun flush() {
        file.parent?.createDirectories()
        val temp = file.resolveSibling("${file.fileName}.tmp")
        temp.outputStream().use { properties.store(it, null) }
        // Se escribe aparte y se renombra para no dejar el archivo a medias si la app se cierra durante la escritura.
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
