package org.uci.proxy.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol

/** Sin estado propio salvo el diálogo de confirmación: recibe los datos y avisa de las acciones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    profiles: List<ProxyProfile>,
    platformLabel: String,
    deleteFailed: Boolean,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissDeleteError: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ProxyProfile?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Perfiles de proxy") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text("Nuevo perfil") }
        },
        bottomBar = {
            Text(
                platformLabel,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Todavía no hay perfiles. Crea uno con «Nuevo perfil».",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(profile, onEdit = { onEdit(profile.id) }, onDelete = { pendingDelete = profile })
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar perfil") },
            text = { Text("Se eliminará «${profile.name}» junto con su contraseña guardada.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(profile.id)
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }

    if (deleteFailed) {
        AlertDialog(
            onDismissRequest = onDismissDeleteError,
            title = { Text("No se pudo eliminar") },
            text = { Text("El perfil no se pudo eliminar. Inténtalo de nuevo.") },
            confirmButton = { TextButton(onClick = onDismissDeleteError) { Text("Aceptar") } },
        )
    }
}

@Composable
private fun ProfileCard(profile: ProxyProfile, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(profile.summary(), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onDelete) { Text("Eliminar") }
            }
        }
    }
}

private fun ProxyProfile.summary(): String {
    val user = if (username.isEmpty()) "" else " · $username"
    return "${protocol.label} · $host:$port$user"
}

internal val ProxyProtocol.label: String
    get() = when (this) {
        ProxyProtocol.HTTP -> "HTTP"
        ProxyProtocol.SOCKS5 -> "SOCKS5"
    }

@Preview
@Composable
private fun ProfileListScreenPreview() {
    MaterialTheme {
        ProfileListScreen(
            profiles = listOf(ProxyProfile("1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")),
            platformLabel = "ProxyUCI en macOS 27.2",
            deleteFailed = false,
            onAdd = {},
            onEdit = {},
            onDelete = {},
            onDismissDeleteError = {},
        )
    }
}
