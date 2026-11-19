package org.uci.proxy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.uci.proxy.theme.ProxyUciTheme
import java.io.File
import kotlin.math.abs
import kotlin.test.fail

/**
 * Capturas de referencia: dibuja composables sin ventana y los compara con una imagen guardada, para detectar
 * cambios de aspecto que nadie buscaba.
 *
 * - Las referencias están en `shared/test@jvm/snapshots/<sistema>/<grupo>/`, una carpeta por sistema operativo
 *   porque cada uno suaviza las letras a su manera. Si falta la del sistema actual, no se compara (y se dice).
 * - Tras un cambio de aspecto buscado, regenerarlas con `UPDATE_SNAPSHOTS=1 ./kotlin test -m shared -p jvm`
 *   y revisar las imágenes antes de hacer commit.
 * - Las capturas de cada ejecución quedan en `build/snapshots/` para mirarlas cuando algo falla.
 */
class Snapshots(private val group: String) {
    private val density = 2f
    private val moduleDir = findModuleDir()
    private val referenceDir = File(moduleDir, "test@jvm/snapshots/${osName()}/$group")
    private val outputDir = File(moduleDir, "../build/snapshots/${osName()}/$group").normalize().apply { mkdirs() }
    private val update = System.getenv("UPDATE_SNAPSHOTS") == "1"
    private val failures = mutableListOf<String>()

    /**
     * Dibuja [content] con el tema de la app en [widthDp] de ancho. Con [heightDp] la imagen tiene ese alto fijo
     * (pantallas completas); sin él, se recorta donde acaba el contenido.
     */
    fun check(name: String, widthDp: Int, heightDp: Int? = null, content: @Composable () -> Unit) {
        val png = render(widthDp, heightDp, content)
        val file = "$name.png"
        File(outputDir, file).writeBytes(png)
        val reference = File(referenceDir, file)
        when {
            update -> {
                referenceDir.mkdirs()
                reference.writeBytes(png)
            }
            !reference.exists() -> println("Sin captura de referencia para ${osName()}/$group/$file: no se compara.")
            else -> difference(reference.readBytes(), png)?.let { failures += "$file: $it (ver ${File(outputDir, file)})" }
        }
    }

    /** Falla con todas las diferencias encontradas, si hubo alguna. */
    fun assertAllMatch() {
        if (failures.isNotEmpty()) fail("Cambió el aspecto de ${failures.size} captura(s) de $group:\n" + failures.joinToString("\n"))
    }

    private fun render(widthDp: Int, heightDp: Int?, content: @Composable () -> Unit): ByteArray {
        val widthPx = (widthDp * density).toInt()
        val heightPx = ((heightDp ?: MAX_HEIGHT_DP) * density).toInt()
        val image = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
            ProxyUciTheme {
                // Fondo en toda la imagen: así el recorte encuentra dónde acaba el contenido.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }.use { scene ->
            // Varios fotogramas para que las fuentes y los iconos estén cargados; tiempo fijo para que las
            // animaciones (el arco de «Conectando», el punto que late) salgan siempre igual.
            scene.render(0)
            scene.render(100_000_000)
            scene.render(500_000_000)
        }
        val result = if (heightDp == null) cropToContent(image) else image
        return result.encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** Recorta el fondo vacío que queda bajo el contenido. */
    private fun cropToContent(image: Image): Image {
        val bitmap = Bitmap.makeFromImage(image)
        val background = bitmap.getColor(0, image.height - 1)
        var bottom = image.height - 1
        outer@ while (bottom > 0) {
            for (x in 0 until image.width step 2) {
                if (bitmap.getColor(x, bottom) != background) break@outer
            }
            bottom--
        }
        val height = (bottom + 1 + (20 * density).toInt()).coerceAtMost(image.height)
        val cropped = Bitmap()
        check(bitmap.extractSubset(cropped, IRect.makeWH(image.width, height))) { "No se pudo recortar la captura" }
        return Image.makeFromBitmap(cropped)
    }

    /**
     * Null si las dos imágenes son iguales salvo detalles de suavizado; si no, por qué difieren.
     * Se toleran píxeles con poca diferencia de color, y hasta un 0,5 % de píxeles distintos.
     */
    private fun difference(expectedPng: ByteArray, actualPng: ByteArray): String? {
        val expected = Bitmap.makeFromImage(Image.makeFromEncoded(expectedPng))
        val actual = Bitmap.makeFromImage(Image.makeFromEncoded(actualPng))
        if (expected.width != actual.width || expected.height != actual.height) {
            return "tamaño ${actual.width}×${actual.height}, se esperaba ${expected.width}×${expected.height}"
        }
        var different = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (channelDistance(expected.getColor(x, y), actual.getColor(x, y)) > 48) different++
            }
        }
        val ratio = different.toDouble() / (expected.width * expected.height)
        return if (ratio > 0.005) "${"%.2f".format(ratio * 100)} % de píxeles distintos" else null
    }

    private fun channelDistance(a: Int, b: Int): Int =
        (0..3).maxOf { shift -> abs(((a shr (shift * 8)) and 0xFF) - ((b shr (shift * 8)) and 0xFF)) }

    private companion object {
        const val MAX_HEIGHT_DP = 2600

        fun osName(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                "mac" in os -> "macos"
                "win" in os -> "windows"
                else -> "linux"
            }
        }

        /** La carpeta del módulo `shared`, se ejecute la prueba desde donde se ejecute. */
        fun findModuleDir(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "test@jvm").isDirectory && File(dir, "module.yaml").isFile) return dir
                val shared = File(dir, "shared")
                if (File(shared, "test@jvm").isDirectory) return shared
                dir = dir.parentFile
            }
            error("No se encontró el módulo shared desde ${System.getProperty("user.dir")}")
        }
    }
}
