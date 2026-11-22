package org.uci.proxy.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons

/**
 * «Detalles» de un fallo, plegados: su código (el mismo que queda en el registro) y lo que dijo el sistema o el
 * servidor, tal cual, con un botón para copiarlo y pedir ayuda. La frase del aviso queda limpia de datos técnicos.
 */
@Composable
internal fun FailureDetails(code: String, detail: String? = null, modifier: Modifier = Modifier) {
    var open by rememberSaveable(code) { mutableStateOf(false) }
    // LocalClipboard (con ClipEntry) no tiene un constructor común a todas las plataformas; el gestor clásico sí.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val text = listOfNotNull(code, detail?.takeIf { it.isNotBlank() }).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        UciButton(
            stringResource(if (open) Res.string.failure_details_hide else Res.string.failure_details_show),
            { open = !open },
            variant = UciButtonVariant.Text,
        )
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.failure_details_code, code),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = ProxyUciTheme.colors.onSurfaceMuted)
                    }
                    Text(
                        stringResource(Res.string.failure_details_log_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ProxyUciTheme.colors.onSurfaceMuted,
                    )
                }
                UciButton(
                    stringResource(Res.string.failure_details_copy),
                    { clipboard.setText(AnnotatedString(text)) },
                    variant = UciButtonVariant.Text,
                    icon = UciIcons.Copy,
                )
            }
        }
    }
}
