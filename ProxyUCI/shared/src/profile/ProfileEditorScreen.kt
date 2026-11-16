package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.profile.ProfileDraft
import org.uci.proxy.core.profile.ProfileField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    onDraftChange: (ProfileDraft) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.draft
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.isNew) "Nuevo perfil" else "Editar perfil") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileTextField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                error = "Escribe un nombre para el perfil.".takeIf { ProfileField.NAME in state.errors },
            )
            ProfileTextField(
                label = "Servidor",
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                error = "Escribe solo el nombre o la IP del servidor, sin http:// ni espacios."
                    .takeIf { ProfileField.HOST in state.errors },
                keyboardType = KeyboardType.Uri,
            )
            ProfileTextField(
                label = "Puerto",
                value = draft.port,
                onValueChange = { onDraftChange(draft.copy(port = it)) },
                error = "El puerto debe ser un número entre 1 y 65535.".takeIf { ProfileField.PORT in state.errors },
                keyboardType = KeyboardType.Number,
            )

            Text("Protocolo", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyProtocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = draft.protocol == protocol,
                        onClick = { onDraftChange(draft.copy(protocol = protocol)) },
                        label = { Text(protocol.label) },
                    )
                }
            }

            ProfileTextField(
                label = "Usuario (opcional)",
                value = draft.username,
                onValueChange = { onDraftChange(draft.copy(username = it)) },
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Contraseña (opcional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.saveFailed) {
                Text(
                    "No se pudo guardar el perfil. Inténtalo de nuevo.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, enabled = !state.isSaving) { Text("Cancelar") }
                Button(onClick = onSave, enabled = !state.isSaving) { Text("Guardar") }
            }
        }
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview
@Composable
private fun ProfileEditorScreenPreview() {
    MaterialTheme {
        ProfileEditorScreen(
            state = ProfileEditorUiState(
                draft = ProfileDraft(name = "UCI", host = "http://proxy", port = "3128"),
                errors = setOf(ProfileField.HOST),
            ),
            onDraftChange = {},
            onPasswordChange = {},
            onSave = {},
            onCancel = {},
        )
    }
}
