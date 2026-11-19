package org.uci.proxy.core.vpn.tailscale

import java.io.File
import java.security.MessageDigest

/** Los métodos nativos de libtsbridge (tools/tsbridge/jni). Los nombres tienen que coincidir con los de la librería. */
internal object TsBridgeNative {
    @JvmStatic external fun version(): String

    @JvmStatic external fun start(config: String): String

    @JvmStatic external fun cancelStart()

    @JvmStatic external fun write(handle: Long, packet: ByteArray, length: Int): Int

    @JvmStatic external fun read(handle: Long, buffer: ByteArray, timeoutMs: Int): Int

    @JvmStatic external fun exitNodes(handle: Long): String

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun nextLog(timeoutMs: Int): String?
}

/**
 * La librería nativa libtsbridge. Se carga la primera vez que se pregunta por [isAvailable]; si no está o no carga, es
 * false y la app no ofrece Tailscale.
 *
 * - En escritorio va en los recursos de la app ([fromResource]) y se copia a un directorio propio antes de cargarla.
 * - En Android va en `jniLibs` ([fromSystem]): Android la instala con la app y basta con [System.loadLibrary].
 *
 * @param load carga la librería y devuelve false si no existe; lanza si existe pero no carga.
 */
class NativeTailscaleBridge(
    private val description: String,
    private val load: () -> Boolean,
    private val onLoadError: (String) -> Unit = {},
) : TailscaleBridge {
    override val isAvailable: Boolean by lazy {
        try {
            load() && TsBridgeNative.version().isNotEmpty()
        } catch (e: Throwable) {
            // UnsatisfiedLinkError o un recurso ilegible: sin Tailscale, pero la app sigue.
            onLoadError("No se pudo cargar la librería de Tailscale ($description): ${e.message}")
            false
        }
    }

    override fun start(configJson: String): String = TsBridgeNative.start(configJson)

    override fun cancelStart() = TsBridgeNative.cancelStart()

    override fun write(handle: Long, packet: ByteArray, length: Int): Int = TsBridgeNative.write(handle, packet, length)

    override fun read(handle: Long, buffer: ByteArray, timeoutMs: Int): Int = TsBridgeNative.read(handle, buffer, timeoutMs)

    override fun exitNodes(handle: Long): String = TsBridgeNative.exitNodes(handle)

    override fun close(handle: Long) = TsBridgeNative.close(handle)

    override fun nextLog(timeoutMs: Int): String? = TsBridgeNative.nextLog(timeoutMs)

    companion object {
        /**
         * Desde los recursos: `native/tsbridge/<resourceDir>/<fileName>`. Se copia a [cacheDir] con un nombre que depende
         * de su contenido (para no pisar una versión en uso) y se carga con [System.load].
         */
        fun fromResource(
            resourceDir: String,
            fileName: String,
            cacheDir: File = File(System.getProperty("java.io.tmpdir"), "proxyuci-native"),
            onLoadError: (String) -> Unit = {},
        ): NativeTailscaleBridge {
            val resource = "/native/tsbridge/$resourceDir/$fileName"
            return NativeTailscaleBridge(resource, load = {
                val bytes = NativeTailscaleBridge::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                if (bytes == null) {
                    false
                } else {
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
                    val target = File(cacheDir, "$digest-$fileName")
                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                        cacheDir.mkdirs()
                        val partial = File(cacheDir, "${target.name}.${System.nanoTime()}.tmp")
                        partial.writeBytes(bytes)
                        if (!partial.renameTo(target) && !target.exists()) error("no se pudo copiar a $target")
                        partial.delete()
                    }
                    System.load(target.absolutePath)
                    true
                }
            }, onLoadError)
        }

        /** Una librería que instala el sistema con la app (los `jniLibs` de Android). */
        fun fromSystem(name: String, onLoadError: (String) -> Unit = {}) = NativeTailscaleBridge("lib$name.so", load = {
            try {
                System.loadLibrary(name)
                true
            } catch (_: UnsatisfiedLinkError) {
                // No viene en esta compilación (o no hay para esta ABI): Tailscale no se ofrece, sin que sea un error.
                false
            }
        }, onLoadError)

        /** La carpeta de la JVM de escritorio para este sistema y arquitectura, o null si no hay librería para él. */
        fun desktopResourceDir(
            osName: String = System.getProperty("os.name").orEmpty(),
            arch: String = System.getProperty("os.arch").orEmpty(),
        ): Pair<String, String>? {
            val cpu = when (arch.lowercase()) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "amd64"
                else -> return null
            }
            val os = osName.lowercase()
            return when {
                os.startsWith("mac") -> "macos-$cpu" to "libtsbridge.dylib"
                os.startsWith("windows") -> "windows-$cpu" to "tsbridge.dll"
                os.startsWith("linux") -> "linux-$cpu" to "libtsbridge.so"
                else -> null
            }
        }
    }
}
