package org.uci.proxy

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import org.slf4j.LoggerFactory
import java.awt.Color
import javax.swing.JFrame

/*
 * La barra de título del sistema, integrada con la app en lugar de ser una franja gris aparte.
 *
 * - macOS: la app dibuja por debajo de una barra transparente y sin título; los botones de cerrar, minimizar y
 *   ampliar quedan sobre el fondo de la app. Arrastrar la ventana y el doble clic siguen siendo los del sistema.
 * - Windows 11: la barra se pinta del color del fondo, con texto claro y en modo oscuro. Se conservan los botones,
 *   el ajuste a las esquinas (Snap) y todo lo nativo. En Windows 10 solo se aplica el modo oscuro.
 */

/** Fondo de la app (el color `Canvas` del tema), para la ventana y la barra de título. */
private val AppBackground = Color(0x0B, 0x0F, 0x17)

/** Texto de la barra en Windows: el `TextPrimary` del tema. */
private val TitleText = Color(0xDF, 0xE2, 0xEE)

/** Alto de la barra de título de macOS: la app no pone nada en esa franja, que es donde están los botones. */
private val MacTitleBarHeight = 28.dp

private val isMac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
private val isWindows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

/**
 * Antes de crear ninguna ventana: en macOS, los elementos del sistema (barra de título, diálogos de archivos) en
 * oscuro como la app, aunque el equipo esté en claro.
 */
fun prepareSystemAppearance() {
    if (isMac) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

/** Integra la barra de título de [window] con la app. Devuelve el alto que la app debe dejar libre arriba. */
fun blendTitleBar(window: JFrame): Dp {
    // Sin esto, al agrandar la ventana se ve un instante blanco antes de que la app se redibuje.
    window.background = AppBackground
    return when {
        isMac -> {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            MacTitleBarHeight
        }
        isWindows -> {
            paintWindowsTitleBar(window)
            0.dp
        }
        else -> 0.dp
    }
}

private fun paintWindowsTitleBar(window: JFrame) {
    try {
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val dwm = Dwmapi.INSTANCE
        dwm.set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
        // Desde Windows 11 (compilación 22000). En Windows 10 devuelven error y la barra queda solo en oscuro.
        dwm.set(hwnd, DWMWA_CAPTION_COLOR, AppBackground.toColorRef())
        dwm.set(hwnd, DWMWA_TEXT_COLOR, TitleText.toColorRef())
    } catch (e: Throwable) {
        // Una barra sin colorear no impide usar la app: se deja la del sistema.
        LoggerFactory.getLogger("ProxyUCI").warn("No se pudo colorear la barra de título: {}", e.message)
    }
}

private fun Dwmapi.set(hwnd: WinDef.HWND, attribute: Int, value: Int) {
    DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), Int.SIZE_BYTES)
}

/** COLORREF de Windows: 0x00BBGGRR. */
private fun Color.toColorRef(): Int = (blue shl 16) or (green shl 8) or red

// Atributos de DwmSetWindowAttribute (dwmapi.h).
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36

@Suppress("FunctionName")
private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    }
}
