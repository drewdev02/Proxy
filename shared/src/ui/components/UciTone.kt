package org.uci.proxy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.uci.proxy.theme.ProxyUciTheme

/**
 * El significado de un elemento, que decide su color. Cada tono tiene un uso concreto y no deben mezclarse:
 * el verde es solo para lo que está bien, el violeta solo para rutas especiales.
 */
enum class UciTone {
    /** Información sin carga: datos, valores por defecto. */
    Neutral,

    /** Algo en marcha o seleccionado: conectando, datos en tránsito. */
    Active,

    /** Correcto: conectado, autenticado, probado. */
    Success,

    /** Rutas especiales: Otro País y túnel dividido. */
    Routing,

    /** Requiere atención pero no es un fallo. */
    Warning,

    /** Fallo o acción destructiva. */
    Danger,
}

/** Los tres colores de un tono: el del texto o icono, el fondo suave y el borde. */
@Immutable
data class UciToneColors(val content: Color, val container: Color, val border: Color)

@Composable
fun UciTone.colors(): UciToneColors {
    val c = ProxyUciTheme.colors
    return when (this) {
        UciTone.Neutral -> UciToneColors(c.onSurfaceMuted, c.control, c.controlBorder)
        UciTone.Active -> UciToneColors(c.active, c.active.copy(alpha = 0.12f), c.active.copy(alpha = 0.4f))
        UciTone.Success -> UciToneColors(c.connected, c.connected.copy(alpha = 0.12f), c.connected.copy(alpha = 0.4f))
        UciTone.Routing -> UciToneColors(c.onRoutingContainer, c.routingContainer, c.routing.copy(alpha = 0.5f))
        UciTone.Warning -> UciToneColors(c.warning, c.warning.copy(alpha = 0.12f), c.warning.copy(alpha = 0.4f))
        UciTone.Danger -> UciToneColors(c.danger, c.danger.copy(alpha = 0.12f), c.danger.copy(alpha = 0.4f))
    }
}
