package org.uci.proxy.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.settings.AppSettings

@Composable
internal fun SettingsRoute(onBack: () -> Unit) {
    val viewModel = koinViewModel<SettingsViewModel>()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onAnswerConnectivityChecksChange = viewModel::onAnswerConnectivityChecksChange,
        onNameByIpChange = viewModel::onNameByIpChange,
        onTryRawIpChange = viewModel::onTryRawIpChange,
        onServiceEnabledChange = viewModel::onServiceEnabledChange,
        onServiceMove = viewModel::onServiceMove,
        onResetServices = viewModel::onResetServices,
    )
}

/** Sin estado propio: muestra [settings] y avisa de lo que se cambia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onAnswerConnectivityChecksChange: (Boolean) -> Unit,
    onNameByIpChange: (Boolean) -> Unit = {},
    onTryRawIpChange: (Boolean) -> Unit = {},
    onServiceEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
    onServiceMove: (String, Int) -> Unit = { _, _ -> },
    onResetServices: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingSwitch(
                title = "Contestar las comprobaciones de conexión",
                description = "Muchos proxies bloquean las pruebas con las que Android, Windows y macOS detectan si hay internet, " +
                    "y el sistema muestra «sin internet» aunque lo haya. Con esto activado, ProxyUCI las contesta por su " +
                    "cuenta mientras el proxy funcione; si el proxy deja de responder, el aviso aparece. " +
                    "Se aplica al conectar la próxima vez.",
                checked = settings.answerConnectivityChecks,
                onCheckedChange = onAnswerConnectivityChecksChange,
            )
            HorizontalDivider()
            NameServicesSection(
                settings = settings,
                onNameByIpChange = onNameByIpChange,
                onTryRawIpChange = onTryRawIpChange,
                onServiceEnabledChange = onServiceEnabledChange,
                onServiceMove = onServiceMove,
                onResetServices = onResetServices,
            )
        }
    }
}

/** Cómo se piden al proxy las IP públicas: por un nombre de estos servicios, probados en orden. */
@Composable
private fun NameServicesSection(
    settings: AppSettings,
    onNameByIpChange: (Boolean) -> Unit,
    onTryRawIpChange: (Boolean) -> Unit,
    onServiceEnabledChange: (String, Boolean) -> Unit,
    onServiceMove: (String, Int) -> Unit,
    onResetServices: () -> Unit,
) {
    SettingSwitch(
        title = "Pedir las IP públicas por nombre",
        description = "El proxy de la UCI rechaza las conexiones a una IP escrita como número, pero acepta el mismo destino " +
            "con un nombre. Estos servicios dan un nombre a cualquier IP. Se prueban en este orden, empezando por el que " +
            "funcionó la última vez. Quien da el nombre se entera de qué IP se piden: no uses servicios en los que no confíes.",
        checked = settings.nameByIp,
        onCheckedChange = onNameByIpChange,
    )
    if (!settings.nameByIp) return

    val services = settings.nameServices
    services.forEachIndexed { index, service ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${index + 1}. ${service.name}", style = MaterialTheme.typography.bodyLarge)
                Text(service.template, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = { onServiceMove(service.id, -1) },
                enabled = index > 0,
                modifier = Modifier.semantics { contentDescription = "Subir ${service.name}" },
            ) { Text("↑") }
            TextButton(
                onClick = { onServiceMove(service.id, 1) },
                enabled = index < services.lastIndex,
                modifier = Modifier.semantics { contentDescription = "Bajar ${service.name}" },
            ) { Text("↓") }
            Switch(
                checked = service.enabled,
                onCheckedChange = { onServiceEnabledChange(service.id, it) },
                modifier = Modifier.semantics { contentDescription = "Usar ${service.name}" },
            )
        }
    }
    SettingSwitch(
        title = "Probar también la IP tal cual",
        description = "Si ningún nombre sirve, se intenta la IP sin nombre como último recurso.",
        checked = settings.tryRawIp,
        onCheckedChange = onTryRawIpChange,
    )
    TextButton(onClick = onResetServices, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Restaurar servicios") }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // El Row entero es el control: el interruptor solo lo dibuja.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(settings = AppSettings(), onBack = {}, onAnswerConnectivityChecksChange = {})
    }
}
