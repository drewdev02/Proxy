package org.uci.proxy.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.settings.NameServiceStatus
import org.uci.proxy.core.settings.ServiceError
import org.uci.proxy.core.settings.ServiceValidation
import kotlin.time.Clock
import org.uci.proxy.theme.ProxyUciTheme

/** Todo lo que la pantalla de Ajustes puede pedir. Agrupado para no arrastrar una docena de parámetros sueltos. */
class SettingsActions(
    /** Null cuando Ajustes es una pestaña de la navegación principal: no hay a dónde volver. */
    val onBack: (() -> Unit)? = null,
    val onAnswerConnectivityChecksChange: (Boolean) -> Unit = {},
    val onNameByIpChange: (Boolean) -> Unit = {},
    val onTryRawIpChange: (Boolean) -> Unit = {},
    val onServiceEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
    val onServiceMove: (String, Int) -> Unit = { _, _ -> },
    val onServiceTest: (String) -> Unit = {},
    val onServiceRemove: (String) -> Unit = {},
    val onAddService: (name: String, template: String) -> ServiceValidation = { _, _ -> ServiceValidation.Invalid(emptySet()) },
    val onResetServices: () -> Unit = {},
)

/** Ajustes a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado. */
@Composable
internal fun SettingsScreen(onBack: (() -> Unit)? = null, viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = state.settings,
        statuses = state.statuses,
        tests = state.tests,
        actions = SettingsActions(
            onBack = onBack,
            onAnswerConnectivityChecksChange = viewModel::onAnswerConnectivityChecksChange,
            onNameByIpChange = viewModel::onNameByIpChange,
            onTryRawIpChange = viewModel::onTryRawIpChange,
            onServiceEnabledChange = viewModel::onServiceEnabledChange,
            onServiceMove = viewModel::onServiceMove,
            onServiceTest = viewModel::testService,
            onServiceRemove = viewModel::removeService,
            onAddService = viewModel::addService,
            onResetServices = viewModel::onResetServices,
        ),
    )
}

/** Sin estado propio salvo el diálogo de añadir: muestra los datos y avisa de lo que se pide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    statuses: Map<String, NameServiceStatus> = emptyMap(),
    tests: Map<String, ServiceTestUi> = emptyMap(),
) {
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { actions.onBack?.let { TextButton(onClick = it) { Text("Volver") } } },
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
                onCheckedChange = actions.onAnswerConnectivityChecksChange,
            )
            HorizontalDivider()
            NameServicesSection(settings, statuses, tests, actions, onAdd = { adding = true })
        }
    }

    if (adding) {
        AddServiceDialog(
            onDismiss = { adding = false },
            onAdd = actions.onAddService,
        )
    }
}

/** Cómo se piden al proxy las IP públicas: por un nombre de estos servicios, probados en orden. */
@Composable
private fun NameServicesSection(
    settings: AppSettings,
    statuses: Map<String, NameServiceStatus>,
    tests: Map<String, ServiceTestUi>,
    actions: SettingsActions,
    onAdd: () -> Unit,
) {
    SettingSwitch(
        title = "Pedir las IP públicas por nombre",
        description = "El proxy de la UCI rechaza las conexiones a una IP escrita como número, pero acepta el mismo destino " +
            "con un nombre. Estos servicios dan un nombre a cualquier IP. Se prueban en este orden, empezando por el que " +
            "funcionó la última vez; uno que el proxy rechaza varias veces seguidas se aparta unos minutos. " +
            "Quien da el nombre se entera de qué IP se piden: no uses servicios en los que no confíes.",
        checked = settings.nameByIp,
        onCheckedChange = actions.onNameByIpChange,
    )
    if (!settings.nameByIp) return

    val now = Clock.System.now().toEpochMilliseconds()
    val services = settings.nameServices
    services.forEachIndexed { index, service ->
        ServiceRow(
            service = service,
            position = index + 1,
            isFirst = index == 0,
            isLast = index == services.lastIndex,
            status = statuses[service.id],
            test = tests[service.id],
            nowMillis = now,
            actions = actions,
        )
    }
    SettingSwitch(
        title = "Probar también la IP tal cual",
        description = "Si ningún nombre sirve, se intenta la IP sin nombre como último recurso.",
        checked = settings.tryRawIp,
        onCheckedChange = actions.onTryRawIpChange,
    )
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onAdd) { Text("Añadir servicio") }
        TextButton(onClick = actions.onResetServices) { Text("Restaurar servicios") }
    }
}

@Composable
private fun ServiceRow(
    service: IpNameService,
    position: Int,
    isFirst: Boolean,
    isLast: Boolean,
    status: NameServiceStatus?,
    test: ServiceTestUi?,
    nowMillis: Long,
    actions: SettingsActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$position. ${service.name}", style = MaterialTheme.typography.bodyLarge)
                Text(service.template, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = service.enabled,
                onCheckedChange = { actions.onServiceEnabledChange(service.id, it) },
                modifier = Modifier.semantics { contentDescription = "Usar ${service.name}" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { actions.onServiceMove(service.id, -1) },
                enabled = !isFirst,
                modifier = Modifier.semantics { contentDescription = "Subir ${service.name}" },
            ) { Text("↑") }
            TextButton(
                onClick = { actions.onServiceMove(service.id, 1) },
                enabled = !isLast,
                modifier = Modifier.semantics { contentDescription = "Bajar ${service.name}" },
            ) { Text("↓") }
            TextButton(
                onClick = { actions.onServiceTest(service.id) },
                enabled = test != ServiceTestUi.Running,
                modifier = Modifier.semantics { contentDescription = "Probar ${service.name}" },
            ) { Text(if (test == ServiceTestUi.Running) "Probando…" else "Probar") }
            if (!service.builtIn) {
                TextButton(
                    onClick = { actions.onServiceRemove(service.id) },
                    modifier = Modifier.semantics { contentDescription = "Eliminar ${service.name}" },
                ) { Text("Eliminar") }
            }
        }
        // La prueba que se acaba de hacer manda sobre lo anotado de usos anteriores.
        val line = test?.message() ?: status?.message(nowMillis) ?: "Sin datos todavía."
        val failed = (test as? ServiceTestUi.Done)?.result.let { it != null && it != NameServiceTestResult.Works } ||
            (test == null && status?.outcome == NameOutcome.REFUSED)
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun NameServiceStatus.message(nowMillis: Long): String = when (outcome) {
    NameOutcome.WORKED -> "Funcionó ${timeAgo(atMillis, nowMillis)}."
    NameOutcome.REFUSED -> "El proxy lo rechazó ${timeAgo(atMillis, nowMillis)}."
}

private fun ServiceTestUi.message(): String? = when (this) {
    ServiceTestUi.Running -> "Probando con el proxy…"
    ServiceTestUi.NoProfile -> "Crea un perfil de proxy para poder probar."
    ServiceTestUi.LocalNetworkDenied -> "La app necesita permiso para acceder a la red local y alcanzar el proxy."
    is ServiceTestUi.Done -> when (val result = result) {
        NameServiceTestResult.Works -> "El proxy acepta este servicio."
        is NameServiceTestResult.Refused -> "El proxy lo rechaza: ${result.detail}"
        NameServiceTestResult.ProxyAuth -> "El proxy rechazó el usuario o la contraseña del perfil: la prueba no vale."
        is NameServiceTestResult.Unreachable ->
            "No se pudo llegar al proxy. ${result.detail} Si el túnel está activo, desconéctalo y prueba otra vez."
        NameServiceTestResult.Timeout -> "El proxy no respondió a tiempo."
    }
}

/** Formulario para añadir un servicio propio. Se cierra solo cuando el servicio es válido y queda guardado. */
@Composable
private fun AddServiceDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, template: String) -> ServiceValidation,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptySet<ServiceError>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir servicio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errors = errors - ServiceError.NAME_EMPTY },
                    label = { Text("Nombre") },
                    singleLine = true,
                    isError = ServiceError.NAME_EMPTY in errors,
                    supportingText = errors.takeIf { ServiceError.NAME_EMPTY in it }?.let { { Text("Escribe un nombre (máximo 40 letras).") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it; errors = errors - TEMPLATE_ERRORS },
                    label = { Text("Plantilla") },
                    placeholder = { Text("{ip-}.mi-dominio.com") },
                    singleLine = true,
                    isError = errors.any { it in TEMPLATE_ERRORS },
                    supportingText = errors.firstOrNull { it in TEMPLATE_ERRORS }?.let { error -> { Text(error.message()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "{ip} se sustituye por la IP con puntos (149.22.82.28) y {ip-} por la IP con guiones (149-22-82-28).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Solo añade servicios en los que confíes: quien da el nombre se entera de qué IP se piden, y uno malicioso " +
                        "podría desviar el tráfico que no va cifrado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val result = onAdd(name, template)) {
                    is ServiceValidation.Valid -> onDismiss()
                    is ServiceValidation.Invalid -> errors = result.errors
                }
            }) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private val TEMPLATE_ERRORS = setOf(
    ServiceError.NO_PLACEHOLDER, ServiceError.INVALID_HOST, ServiceError.LOCAL_DOMAIN, ServiceError.DUPLICATE,
)

private fun ServiceError.message(): String = when (this) {
    ServiceError.NAME_EMPTY -> "Escribe un nombre (máximo 40 letras)."
    ServiceError.NO_PLACEHOLDER -> "La plantilla debe llevar {ip} o {ip-}, donde irá la IP."
    ServiceError.INVALID_HOST -> "Con una IP puesta, no da un nombre de dominio válido (letras, cifras, guiones y puntos)."
    ServiceError.LOCAL_DOMAIN -> "Ese dominio es local o reservado: el proxy no podría resolverlo."
    ServiceError.DUPLICATE -> "Ya hay un servicio con esa plantilla."
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
    ProxyUciTheme {
        SettingsScreen(
            settings = AppSettings(nameServices = IpNameService.BUILT_IN + IpNameService("c", "Mío", "{ip-}.mio.com")),
            actions = SettingsActions(),
            statuses = mapOf(
                "sslip" to NameServiceStatus(NameOutcome.REFUSED, 0),
                "nip" to NameServiceStatus(NameOutcome.WORKED, 0),
            ),
        )
    }
}
