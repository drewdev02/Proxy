package org.uci.proxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.icons.UciIcons

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = ProxyUciTheme.colors.control,
    unfocusedContainerColor = ProxyUciTheme.colors.control,
    disabledContainerColor = ProxyUciTheme.colors.card,
    errorContainerColor = ProxyUciTheme.colors.control,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = ProxyUciTheme.colors.controlBorder,
    disabledBorderColor = ProxyUciTheme.colors.cardBorder,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = ProxyUciTheme.colors.onSurfaceMuted,
    unfocusedPlaceholderColor = ProxyUciTheme.colors.onSurfaceFaint,
    focusedPlaceholderColor = ProxyUciTheme.colors.onSurfaceFaint,
)

/**
 * Campo de texto con la etiqueta encima (no dentro), como en el diseño.
 * - [mono]: para host, puerto, IP o rutas; usa la letra monoespaciada.
 * - [error]: si no es null, el campo se marca en rojo y se muestra este texto debajo en lugar de [supportingText].
 */
@Composable
fun UciTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    error: String? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ProxyUciTheme.colors.onSurfaceMuted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = if (mono) ProxyUciTheme.mono.metricSmall.copy(color = MaterialTheme.colorScheme.onSurface) else MaterialTheme.typography.bodyLarge,
            // La etiqueta visible está fuera del campo; aquí se repite para los lectores de pantalla.
            label = null,
            placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
            trailingIcon = trailing,
            isError = error != null,
            supportingText = (error ?: supportingText)?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            shape = MaterialTheme.shapes.medium,
            colors = fieldColors(),
        )
    }
}

/** Campo de contraseña con el botón del ojo para mostrarla u ocultarla. */
@Composable
fun UciPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    UciTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = UciIcons.Lock,
        supportingText = supportingText,
        error = error,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            UciIconButton(
                icon = if (visible) UciIcons.VisibilityOff else UciIcons.Visibility,
                contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                onClick = { visible = !visible },
                bordered = false,
            )
        },
    )
}

/** Buscador: sin etiqueta, con lupa, y una X para vaciarlo cuando tiene texto. */
@Composable
fun UciSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(UciIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = when {
            query.isNotEmpty() -> {
                { UciIconButton(UciIcons.Close, "Borrar búsqueda", { onQueryChange("") }, bordered = false) }
            }
            else -> trailing
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.medium,
        colors = fieldColors(),
    )
}

/**
 * Interruptor del diseño: pista azul intenso encendido, gris pizarra apagado, botón blanco.
 * Normalmente va dentro de [UciSwitchRow], que hace pulsable toda la fila; suelto, pasar [onCheckedChange].
 */
@Composable
fun UciSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ProxyUciTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.switchThumb,
            checkedTrackColor = c.switchTrackOn,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = c.onSurfaceMuted,
            uncheckedTrackColor = c.switchTrackOff,
            uncheckedBorderColor = c.controlBorder,
            disabledCheckedTrackColor = c.switchTrackOn.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = c.card,
        ),
    )
}

/** Casilla de verificación. Como el interruptor, suele ir dentro de una fila pulsable ([UciCheckboxRow]). */
@Composable
fun UciCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = ProxyUciTheme.colors.switchTrackOn,
            uncheckedColor = ProxyUciTheme.colors.controlBorder,
            checkmarkColor = ProxyUciTheme.colors.switchThumb,
        ),
    )
}

/**
 * Selector de una opción entre pocas (2 a 4), como NTLMv2 / Basic / Sin Auth.
 * [label] da el texto de cada opción y [description] una segunda línea opcional, como «Modo inclusivo».
 */
@Composable
fun <T> UciSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    description: ((T) -> String?)? = null,
    icon: (@Composable (T) -> ImageVector?)? = null,
    enabled: Boolean = true,
) {
    val c = ProxyUciTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.card, MaterialTheme.shapes.medium)
            .border(1.dp, c.cardBorder, MaterialTheme.shapes.medium)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val content = when {
                !enabled -> c.onSurfaceFaint
                isSelected -> MaterialTheme.colorScheme.primary
                else -> c.onSurfaceMuted
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) c.control else Color.Transparent, MaterialTheme.shapes.small)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    icon?.invoke(option)?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(16.dp)) }
                    Text(label(option), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                description?.invoke(option)?.let {
                    Text(it, style = ProxyUciTheme.mono.badge, color = c.onSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Archivo elegido (por ejemplo el `.ovpn` de Otro País) con un botón para elegir otro.
 * Con [fileName] null muestra [emptyText] y el botón dice [chooseLabel].
 */
@Composable
fun UciFileField(
    fileName: String?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    emptyText: String = "Ningún archivo elegido",
    chooseLabel: String = "Elegir archivo",
    changeLabel: String = "Cambiar",
    enabled: Boolean = true,
) {
    val c = ProxyUciTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.control, MaterialTheme.shapes.medium)
            .border(1.dp, c.controlBorder, MaterialTheme.shapes.medium)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(UciIcons.File, contentDescription = null, tint = if (fileName != null) MaterialTheme.colorScheme.primary else c.onSurfaceFaint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                fileName ?: emptyText,
                style = if (fileName != null) ProxyUciTheme.mono.metricSmall else MaterialTheme.typography.bodyMedium,
                color = if (fileName != null) MaterialTheme.colorScheme.onSurface else c.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null && fileName != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        UciButton(
            text = if (fileName != null) changeLabel else chooseLabel,
            onClick = onPick,
            variant = UciButtonVariant.Text,
            enabled = enabled,
        )
    }
}

/** Separador vertical fino, para dividir valores en una misma línea. */
@Composable
internal fun UciDot(modifier: Modifier = Modifier) {
    Box(modifier.size(3.dp).background(ProxyUciTheme.colors.onSurfaceFaint, CircleShape))
}
