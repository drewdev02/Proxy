package org.uci.proxy.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Formas: 8 dp por defecto; tarjetas y hojas inferiores a 16 dp.
 * Las etiquetas pequeñas son píldoras para distinguirlas de las tarjetas que se tocan.
 */
internal val ProxyUciShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Espaciado sobre una base de 4 dp. */
@Immutable
data class ProxyUciSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val gutterSmall: Dp = 12.dp,
    val md: Dp = 16.dp,
    /** Margen lateral de la pantalla en móvil. */
    val screenMargin: Dp = 20.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    /** Margen lateral en tableta y escritorio. */
    val screenMarginWide: Dp = 32.dp,
)

private val LocalProxyUciColors = staticCompositionLocalOf { ProxyUciColors() }
private val LocalProxyUciSpacing = staticCompositionLocalOf { ProxyUciSpacing() }
private val LocalProxyUciMono = staticCompositionLocalOf<ProxyUciMonoTypography> {
    error("ProxyUciTheme no está aplicado")
}

/** Tema de la app: Material 3 con la paleta, tipografía y formas del diseño, más sus tokens propios. */
@Composable
fun ProxyUciTheme(content: @Composable () -> Unit) {
    val (inter, mono) = rememberProxyUciFonts()
    val typography = remember(inter) { proxyUciTypography(inter) }
    val monoTypography = remember(mono) { proxyUciMonoTypography(mono) }

    CompositionLocalProvider(
        LocalProxyUciColors provides ProxyUciColors(),
        LocalProxyUciSpacing provides ProxyUciSpacing(),
        LocalProxyUciMono provides monoTypography,
    ) {
        MaterialTheme(
            colorScheme = ProxyUciColorScheme,
            typography = typography,
            shapes = ProxyUciShapes,
            content = content,
        )
    }
}

/** Acceso a los tokens propios: `ProxyUciTheme.colors.connected`, `ProxyUciTheme.mono.badge`... */
object ProxyUciTheme {
    val colors: ProxyUciColors
        @Composable @ReadOnlyComposable get() = LocalProxyUciColors.current

    val spacing: ProxyUciSpacing
        @Composable @ReadOnlyComposable get() = LocalProxyUciSpacing.current

    val mono: ProxyUciMonoTypography
        @Composable @ReadOnlyComposable get() = LocalProxyUciMono.current
}
