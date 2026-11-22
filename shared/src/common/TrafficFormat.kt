package org.uci.proxy.common

import androidx.compose.runtime.Composable
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*

/**
 * Una cantidad de bytes legible, separada en cifra y unidad para dibujarlas con estilos distintos: «1,42» «GB».
 * Base 1024, con [decimalSeparator] (la coma en español), y como mucho tres cifras significativas (sin ceros sobrantes al
 * final). Las unidades son universales; el separador depende del idioma: en la interfaz, [decimalSeparator].
 */
fun formatBytes(bytes: Long, decimalSeparator: Char): Pair<String, String> {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    // Lo que redondea a 1024 sube de unidad: «1 MB», no «1024 KB».
    if (unit < UNITS.lastIndex && decimalsFor(value).let { round(value, it) } >= 1024) {
        value /= 1024
        unit++
    }
    val text = if (unit == 0) bytes.coerceAtLeast(0).toString() else formatDecimal(value, decimalsFor(value), decimalSeparator)
    return text to UNITS[unit]
}

/** Una velocidad en bytes por segundo: «4,8» «MB/s». */
fun formatRate(bytesPerSecond: Long, decimalSeparator: Char): Pair<String, String> =
    formatBytes(bytesPerSecond, decimalSeparator).let { (value, unit) -> value to "$unit/s" }

/** La cantidad en una sola cadena, para textos y lectores de pantalla: «1,42 GB». */
fun bytesText(bytes: Long, decimalSeparator: Char): String =
    formatBytes(bytes, decimalSeparator).let { (value, unit) -> "$value $unit" }

/** El separador decimal del idioma de la interfaz: «,» en español, «.» en inglés. */
@Composable
fun decimalSeparator(): Char = stringResource(Res.string.format_decimal_separator).first()

/** [decimalSeparator] fuera de la interfaz (notificaciones). */
suspend fun loadDecimalSeparator(): Char = getString(Res.string.format_decimal_separator).first()

/** Un porcentaje entero como se escribe en el idioma de la interfaz: «71 %» en español, «71%» en inglés. */
@Composable
fun percentText(percent: Int): String = stringResource(Res.string.format_percent, percent)

/** [percentText] fuera de la interfaz (notificaciones). */
suspend fun loadPercentText(percent: Int): String = getString(Res.string.format_percent, percent)

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

private fun decimalsFor(value: Double): Int = when {
    value < 10 -> 2
    value < 100 -> 1
    else -> 0
}

private fun round(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return (value * factor).roundToLong() / factor
}

private fun formatDecimal(value: Double, decimals: Int, separator: Char): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (value * factor).roundToLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole$separator$fraction"
}
