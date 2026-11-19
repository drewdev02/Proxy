package org.uci.proxy.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Paleta del sistema de diseño "ProxyUCI Protocol Design" (proyecto de Google Stitch).
 * Solo hay tema oscuro: está pensado para pantallas OLED y la app no ofrece uno claro.
 *
 * Significado de cada tono, que no conviene mezclar:
 * - Azul (#38BDF8): actividad, datos en tránsito y foco.
 * - Verde (#10B981): solo estados correctos (túnel conectado, proxy autenticado).
 * - Violeta (#8B5CF6): rutas especiales, Otro País y túnel dividido.
 */

// Escalones de superficie: la profundidad se marca con bordes y cambios de tono, nunca con sombras negras.
internal val Canvas = Color(0xFF0B0F17)
internal val CanvasLowest = Color(0xFF080B12)
internal val Card = Color(0xFF111827)
internal val CardBorder = Color(0xFF1E293B)
internal val Control = Color(0xFF1E293B)
internal val ControlBorder = Color(0xFF334155)

internal val TextPrimary = Color(0xFFDFE2EE)
internal val TextSecondary = Color(0xFFBDC8D1)
internal val Outline = Color(0xFF87929A)

internal val Cyan = Color(0xFF38BDF8)
internal val CyanDeep = Color(0xFF0284C7)
internal val Emerald = Color(0xFF10B981)
internal val Violet = Color(0xFF8B5CF6)
internal val VioletLight = Color(0xFFA78BFA)
internal val Slate = Color(0xFF64748B)
internal val Crimson = Color(0xFFF43F5E)
internal val Amber = Color(0xFFFBBF24)

internal val ProxyUciColorScheme: ColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00354A),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    inversePrimary = Color(0xFF00668A),
    secondary = Emerald,
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = VioletLight,
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF1E1B4B),
    onTertiaryContainer = Color(0xFFC4B5FD),
    background = Canvas,
    onBackground = TextPrimary,
    surface = Canvas,
    onSurface = TextPrimary,
    surfaceVariant = Control,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Cyan,
    inverseSurface = TextPrimary,
    inverseOnSurface = Color(0xFF2C3039),
    error = Crimson,
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFECDD3),
    outline = Outline,
    outlineVariant = CardBorder,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353942),
    surfaceDim = Canvas,
    surfaceContainerLowest = CanvasLowest,
    surfaceContainerLow = Card,
    surfaceContainer = Card,
    // Las Card de Material usan el escalón más alto: se deja en el tono de los controles para que no destaquen de más.
    surfaceContainerHigh = Color(0xFF172033),
    surfaceContainerHighest = Control,
)

/** Colores del diseño que Material 3 no tiene: estados del túnel, latencia y bordes. */
@Immutable
data class ProxyUciColors(
    /** Túnel conectado, operación correcta. */
    val connected: Color = Emerald,
    /** Fondo teñido del botón central cuando está conectado. */
    val connectedTint: Color = Color(0x26064E3B),
    /** Halo del botón central conectado (capa exterior y núcleo). */
    val connectedGlowOuter: Color = Color(0x3310B981),
    val connectedGlowCore: Color = Color(0x5910B981),
    /** Conectando, datos en tránsito. */
    val active: Color = Cyan,
    /** Desconectado o en reposo. */
    val idle: Color = Slate,
    /** Fallo de autenticación o paquetes perdidos. */
    val danger: Color = Crimson,
    val warning: Color = Amber,
    /** Rutas especiales: Otro País y túnel dividido. */
    val routing: Color = Violet,
    val routingContainer: Color = Color(0xFF1E1B4B),
    val onRoutingContainer: Color = VioletLight,
    /** Latencia por debajo de 50 ms, entre 50 y 150 ms, y por encima de 150 ms. */
    val latencyGood: Color = Emerald,
    val latencyFair: Color = Amber,
    val latencyPoor: Color = Crimson,
    /** Tarjetas (nivel 1): fondo y borde de 1 px. */
    val card: Color = Card,
    val cardBorder: Color = CardBorder,
    /** Controles y campos (nivel 2): fondo y borde. */
    val control: Color = Control,
    val controlBorder: Color = ControlBorder,
    /** Interruptores: pista encendida y apagada, y botón. */
    val switchTrackOn: Color = CyanDeep,
    val switchTrackOff: Color = Control,
    val switchThumb: Color = Color(0xFFF8FAFC),
) {
    /** Color según la latencia medida, con los tres umbrales del diseño. */
    fun latency(millis: Long): Color = when {
        millis < 50 -> latencyGood
        millis <= 150 -> latencyFair
        else -> latencyPoor
    }
}
