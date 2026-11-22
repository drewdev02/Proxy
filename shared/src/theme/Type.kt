package org.uci.proxy.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.inter_bold
import org.uci.proxy.resources.inter_medium
import org.uci.proxy.resources.inter_regular
import org.uci.proxy.resources.inter_semibold
import org.uci.proxy.resources.jetbrains_mono_medium
import org.uci.proxy.resources.jetbrains_mono_regular
import org.uci.proxy.resources.jetbrains_mono_semibold

/*
 * Dos familias: Inter para la interfaz y JetBrains Mono para lo técnico
 * (IP, puertos, velocidades, latencias y etiquetas de estado). Con la monoespaciada
 * las cifras ocupan siempre lo mismo y no mueven la pantalla al actualizarse.
 */

@Composable
internal fun interFamily() = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
    Font(Res.font.inter_bold, FontWeight.Bold),
)

@Composable
internal fun monoFamily() = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.em,
)

/** Escala de Material 3 con los tamaños del diseño. */
internal fun proxyUciTypography(inter: FontFamily) = Typography(
    displayLarge = style(inter, 40, 48, FontWeight.Bold, -0.02),
    displayMedium = style(inter, 32, 40, FontWeight.Bold, -0.02),
    displaySmall = style(inter, 28, 36, FontWeight.Bold, -0.01),
    headlineLarge = style(inter, 28, 36, FontWeight.SemiBold, -0.01),
    headlineMedium = style(inter, 24, 32, FontWeight.SemiBold, -0.01),
    headlineSmall = style(inter, 20, 28, FontWeight.SemiBold),
    titleLarge = style(inter, 20, 28, FontWeight.SemiBold),
    titleMedium = style(inter, 16, 24, FontWeight.SemiBold),
    titleSmall = style(inter, 14, 20, FontWeight.SemiBold),
    bodyLarge = style(inter, 16, 24, FontWeight.Normal),
    bodyMedium = style(inter, 14, 20, FontWeight.Normal),
    bodySmall = style(inter, 12, 16, FontWeight.Normal),
    labelLarge = style(inter, 14, 20, FontWeight.Medium),
    labelMedium = style(inter, 12, 16, FontWeight.Medium, 0.02),
    labelSmall = style(inter, 11, 16, FontWeight.Medium, 0.02),
)

/** Estilos monoespaciados del diseño, que Material 3 no tiene. */
@Immutable
data class ProxyUciMonoTypography(
    /** Cifra grande de una tarjeta de datos (velocidad, volumen). */
    val metricLarge: TextStyle,
    /** Cifra pequeña: latencia, contadores. */
    val metricSmall: TextStyle,
    /** Dirección, puerto o valor técnico dentro de un texto normal. */
    val code: TextStyle,
    /** Etiqueta de estado en mayúsculas (DIRECT, PROXY, TUNNEL) y unidades (MB/s, ms). */
    val badge: TextStyle,
)

internal fun proxyUciMonoTypography(mono: FontFamily) = ProxyUciMonoTypography(
    metricLarge = style(mono, 32, 36, FontWeight.SemiBold, -0.03),
    metricSmall = style(mono, 14, 18, FontWeight.Medium),
    code = style(mono, 13, 18, FontWeight.Normal),
    badge = style(mono, 11, 14, FontWeight.Medium, 0.04),
)

/** Familias cargadas una sola vez por composición. */
@Composable
internal fun rememberProxyUciFonts(): Pair<FontFamily, FontFamily> {
    val inter = interFamily()
    val mono = monoFamily()
    return remember(inter, mono) { inter to mono }
}
