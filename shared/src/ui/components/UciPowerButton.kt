package org.uci.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.icons.UciIcons

/** Los estados del botón de conexión. */
enum class UciPowerState { Idle, Connecting, Connected, Error }

/**
 * El botón central de conexión (140 dp en móvil).
 * - En reposo: gris.
 * - Conectando: un arco azul gira alrededor.
 * - Conectado: verde con un halo suave.
 * - Error: borde rojo.
 *
 * [label] es el texto bajo el icono («Conectar», «Conectado»...); también es lo que oyen los lectores de pantalla.
 */
@Composable
fun UciPowerButton(
    state: UciPowerState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 140.dp,
) {
    val colors = ProxyUciTheme.colors
    val accent by animateColorAsState(
        when (state) {
            UciPowerState.Idle -> colors.idle
            UciPowerState.Connecting -> colors.active
            UciPowerState.Connected -> colors.connected
            UciPowerState.Error -> colors.danger
        },
    )
    val fill by animateColorAsState(
        when (state) {
            UciPowerState.Connected -> colors.connectedTint
            UciPowerState.Error -> colors.danger.copy(alpha = 0.08f)
            else -> colors.card
        },
    )

    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
    )
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
    )

    val glowOuter = colors.connectedGlowOuter
    val glowCore = colors.connectedGlowCore
    val ringBase = colors.controlBorder

    Box(
        modifier = modifier
            .size(diameter + 48.dp)
            .drawBehind {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = diameter.toPx() / 2
                if (state == UciPowerState.Connected) {
                    // Halo en dos capas: una amplia y tenue, y un núcleo más intenso pegado al borde.
                    drawCircle(
                        Brush.radialGradient(listOf(glowOuter.copy(alpha = glowOuter.alpha * pulse), Color.Transparent), center, radius + 24.dp.toPx()),
                        radius + 24.dp.toPx(),
                        center,
                    )
                    drawCircle(
                        Brush.radialGradient(listOf(glowCore, Color.Transparent), center, radius + 8.dp.toPx()),
                        radius + 8.dp.toPx(),
                        center,
                    )
                }
                // Anillo exterior fijo.
                val ring = radius + 10.dp.toPx()
                drawCircle(ringBase.copy(alpha = 0.5f), ring, center, style = Stroke(1.dp.toPx()))
                if (state == UciPowerState.Connecting) {
                    val stroke = 3.dp.toPx()
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                        startAngle = rotation,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(center.x - ring, center.y - ring),
                        size = Size(ring * 2, ring * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, accent, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = when (state) {
                        UciPowerState.Idle -> "Desconectado"
                        UciPowerState.Connecting -> "Conectando"
                        UciPowerState.Connected -> "Conectado"
                        UciPowerState.Error -> "Error"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(UciIcons.Power, contentDescription = null, tint = accent, modifier = Modifier.size(diameter * 0.32f))
            Text(label.uppercase(), style = ProxyUciTheme.mono.badge, color = accent)
        }
    }
}
