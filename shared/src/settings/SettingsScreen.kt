package org.uci.proxy.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.settings.NameServiceStatus
import org.uci.proxy.core.settings.ServiceError
import org.uci.proxy.core.settings.ServiceValidation
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciReorderableList
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons

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
            onAnswerConnectivityChecksChange = viewModel::setAnswerConnectivityChecks,
            onNameByIpChange = viewModel::setNameByIp,
            onTryRawIpChange = viewModel::setTryRawIp,
            onServiceEnabledChange = viewModel::setServiceEnabled,
            onServiceMove = viewModel::moveService,
            onServiceTest = viewModel::testService,
            onServiceRemove = viewModel::removeService,
            onAddService = viewModel::addService,
            onResetServices = viewModel::resetServices,
        ),
    )
}

/**
 * Ajustes sin estado (salvo la hoja de añadir servicio): muestra los datos y avisa de lo que se pide. Una tarjeta por
 * tema; los cambios se aplican al conectar la próxima vez.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    statuses: Map<String, NameServiceStatus> = emptyMap(),
    tests: Map<String, ServiceTestUi> = emptyMap(),
) {
    var adding by remember { mutableStateOf(false) }
    val spacing = ProxyUciTheme.spacing

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        UciTopBar(title = "Ajustes", subtitle = "Se aplican al conectar la próxima vez", onBack = actions.onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenMargin, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                UciSectionCard(
                    title = "Conexión",
                    subtitle = "Cómo se comporta el túnel con el sistema",
                    icon = UciIcons.CheckCircle,
                ) {
                    UciSwitchRow(
                        title = "Contestar las comprobaciones de conexión",
                        subtitle = "Muchos proxies bloquean las pruebas con las que Android, Windows y macOS detectan si hay " +
                            "internet, y el sistema muestra «sin internet» aunque lo haya. ProxyUCI las contesta mientras el " +
                            "proxy funcione; si deja de responder, el aviso aparece.",
                        checked = settings.answerConnectivityChecks,
                        onCheckedChange = actions.onAnswerConnectivityChecksChange,
                    )
                }
                NameServicesCard(settings, statuses, tests, actions, onAdd = { adding = true })
                Spacer(Modifier.height(spacing.md))
            }
        }
    }

    if (adding) {
        AddServiceSheet(onDismiss = { adding = false }, onAdd = actions.onAddService)
    }
}

/** Cómo se piden al proxy las IP públicas: por un nombre de estos servicios, probados en orden. */
@Composable
private fun NameServicesCard(
    settings: AppSettings,
    statuses: Map<String, NameServiceStatus>,
    tests: Map<String, ServiceTestUi>,
    actions: SettingsActions,
    onAdd: () -> Unit,
) {
    UciSectionCard(
        title = "Servicios de nombres",
        subtitle = "Para pedir al proxy las IP públicas por un nombre",
        icon = UciIcons.Dns,
        trailing = { UciSwitch(settings.nameByIp, actions.onNameByIpChange) },
    ) {
        Text(
            "El proxy de la UCI rechaza las conexiones a una IP escrita como número, pero acepta el mismo destino con un " +
                "nombre. Estos servicios dan un nombre a cualquier IP. Se prueban en este orden (arrastra para cambiarlo), " +
                "empezando por el que funcionó la última vez; uno que el proxy rechaza varias veces seguidas se aparta unos minutos.",
            style = MaterialTheme.typography.bodySmall,
            color = ProxyUciTheme.colors.onSurfaceMuted,
        )
        if (!settings.nameByIp) return@UciSectionCard

        val now = Clock.System.now().toEpochMilliseconds()
        val services = settings.nameServices
        UciReorderableList(
            items = services,
            onMove = { from, to -> actions.onServiceMove(services[from].id, to - from) },
        ) { service ->
            ServiceContent(
                service = service,
                position = services.indexOf(service) + 1,
                status = statuses[service.id],
                test = tests[service.id],
                nowMillis = now,
                actions = actions,
            )
        }
        UciDivider()
        UciSwitchRow(
            title = "Probar también la IP tal cual",
            subtitle = "Si ningún nombre sirve, se intenta la IP sin nombre como último recurso.",
            checked = settings.tryRawIp,
            onCheckedChange = actions.onTryRawIpChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UciButton("Añadir servicio", onAdd, variant = UciButtonVariant.Secondary, icon = UciIcons.Add, modifier = Modifier.weight(1f))
            UciButton("Restaurar", actions.onResetServices, variant = UciButtonVariant.Text, icon = UciIcons.Refresh)
        }
        UciInlineAlert(
            "Quien da el nombre se entera de qué IP se piden: no uses servicios en los que no confíes.",
            tone = UciTone.Warning,
        )
    }
}

/** Lo que muestra cada servicio dentro de la lista ordenable: posición, nombre, plantilla, estado y controles. */
@Composable
private fun RowScope.ServiceContent(
    service: IpNameService,
    position: Int,
    status: NameServiceStatus?,
    test: ServiceTestUi?,
    nowMillis: Long,
    actions: SettingsActions,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$position", style = ProxyUciTheme.mono.badge, color = ProxyUciTheme.colors.onSurfaceFaint)
            Column(Modifier.weight(1f)) {
                Text(service.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(service.template, style = ProxyUciTheme.mono.code, color = ProxyUciTheme.colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            UciSwitch(
                checked = service.enabled,
                onCheckedChange = { actions.onServiceEnabledChange(service.id, it) },
                modifier = Modifier.semantics { contentDescription = "Usar ${service.name}" },
            )
        }
        // La prueba que se acaba de hacer manda sobre lo anotado de usos anteriores.
        val (line, tone) = test?.message() ?: status?.message(nowMillis) ?: ("Sin datos todavía" to UciTone.Neutral)
        Text(line, style = MaterialTheme.typography.bodySmall, color = if (tone == UciTone.Neutral) ProxyUciTheme.colors.onSurfaceFaint else tone.colors().content)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UciButton(
                if (test == ServiceTestUi.Running) "Probando…" else "Probar",
                { actions.onServiceTest(service.id) },
                variant = UciButtonVariant.Text,
                icon = UciIcons.Speed,
                loading = test == ServiceTestUi.Running,
            )
            if (service.builtIn) {
                UciStatusPill("De fábrica")
            } else {
                UciButton("Eliminar", { actions.onServiceRemove(service.id) }, variant = UciButtonVariant.Text, icon = UciIcons.Delete)
            }
        }
    }
}

private fun NameServiceStatus.message(nowMillis: Long): Pair<String, UciTone> = when (outcome) {
    NameOutcome.WORKED -> "Funcionó ${timeAgo(atMillis, nowMillis)}" to UciTone.Success
    NameOutcome.REFUSED -> "El proxy lo rechazó ${timeAgo(atMillis, nowMillis)}" to UciTone.Danger
}

private fun ServiceTestUi.message(): Pair<String, UciTone> = when (this) {
    ServiceTestUi.Running -> "Probando con el proxy…" to UciTone.Active
    ServiceTestUi.NoProfile -> "Crea un perfil de proxy para poder probar." to UciTone.Warning
    ServiceTestUi.LocalNetworkDenied -> "La app necesita permiso para acceder a la red local y alcanzar el proxy." to UciTone.Warning
    is ServiceTestUi.Done -> when (val result = result) {
        NameServiceTestResult.Works -> "El proxy acepta este servicio." to UciTone.Success
        is NameServiceTestResult.Refused -> "El proxy lo rechaza: ${result.detail}" to UciTone.Danger
        NameServiceTestResult.ProxyAuth -> "El proxy rechazó el usuario o la contraseña del perfil: la prueba no vale." to UciTone.Warning
        is NameServiceTestResult.Unreachable ->
            "No se pudo llegar al proxy. ${result.detail} Si el túnel está activo, desconéctalo y prueba otra vez." to UciTone.Danger
        NameServiceTestResult.Timeout -> "El proxy no respondió a tiempo." to UciTone.Warning
    }
}

/** Formulario para añadir un servicio propio. Se cierra solo cuando el servicio es válido y queda guardado. */
@Composable
private fun AddServiceSheet(
    onDismiss: () -> Unit,
    onAdd: (name: String, template: String) -> ServiceValidation,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptySet<ServiceError>()) }

    UciBottomSheet("Añadir servicio", onDismiss = onDismiss) {
        UciTextField(
            value = name,
            onValueChange = { name = it; errors = errors - ServiceError.NAME_EMPTY },
            label = "Nombre",
            placeholder = "p. ej. Mi dominio",
            error = ServiceError.NAME_EMPTY.message().takeIf { ServiceError.NAME_EMPTY in errors },
        )
        UciTextField(
            value = template,
            onValueChange = { template = it; errors = errors - TEMPLATE_ERRORS },
            label = "Plantilla",
            placeholder = "{ip-}.mi-dominio.com",
            mono = true,
            supportingText = "{ip} es la IP con puntos (149.22.82.28) y {ip-} con guiones (149-22-82-28).",
            error = errors.firstOrNull { it in TEMPLATE_ERRORS }?.message(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        UciInlineAlert(
            "Solo añade servicios en los que confíes: quien da el nombre se entera de qué IP se piden, y uno malicioso " +
                "podría desviar el tráfico que no va cifrado.",
            tone = UciTone.Warning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            UciButton("Cancelar", onDismiss, variant = UciButtonVariant.Text)
            UciButton("Añadir", {
                when (val result = onAdd(name, template)) {
                    is ServiceValidation.Valid -> onDismiss()
                    is ServiceValidation.Invalid -> errors = result.errors
                }
            }, icon = UciIcons.Add)
        }
    }
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
