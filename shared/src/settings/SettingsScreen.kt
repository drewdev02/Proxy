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
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.uci.proxy.core.proxy.NameServiceTestResult
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService
import org.uci.proxy.core.settings.NameOutcome
import org.uci.proxy.core.settings.NameServiceStatus
import org.uci.proxy.core.settings.ServiceError
import org.uci.proxy.core.settings.ServiceValidation
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
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
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.core.settings.QuotaSettings
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.components.colors
import org.uci.proxy.ui.icons.UciIcons
import org.koin.compose.koinInject
import org.uci.proxy.common.proxyReason
import org.uci.proxy.i18n.AppLanguage
import org.uci.proxy.i18n.AppLanguageSetting

/** Todo lo que la pantalla de Ajustes puede pedir. Agrupado para no arrastrar una docena de parámetros sueltos. */
class SettingsActions(
    /** Null cuando Ajustes es una pestaña de la navegación principal: no hay a dónde volver. */
    val onBack: (() -> Unit)? = null,
    val onAnswerConnectivityChecksChange: (Boolean) -> Unit = {},
    val onIntranetDirectChange: (Boolean) -> Unit = {},
    val onVpnFallbackToProxyChange: (Boolean) -> Unit = {},
    val onNameByIpChange: (Boolean) -> Unit = {},
    val onTryRawIpChange: (Boolean) -> Unit = {},
    val onServiceEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
    val onServiceMove: (String, Int) -> Unit = { _, _ -> },
    val onServiceTest: (String) -> Unit = {},
    val onServiceRemove: (String) -> Unit = {},
    val onAddService: (name: String, template: String) -> ServiceValidation = { _, _ -> ServiceValidation.Invalid(emptySet()) },
    val onResetServices: () -> Unit = {},
    val onQuotaAlertChange: (Int, Boolean) -> Unit = { _, _ -> },
    val onQuotaCheckMinutesChange: (Int) -> Unit = {},
    val onQuotaPauseChange: (Boolean) -> Unit = {},
    val onQuotaVerifyCertificateChange: (Boolean) -> Unit = {},
    /** Null fuera del modo desarrollo: entonces no se ofrece cambiar la dirección del servicio. */
    val onQuotaServiceUrlChange: ((String) -> Unit)? = null,
    val onLanguageChange: (AppLanguage) -> Unit = {},
)

/** Ajustes a nivel de pantalla: obtiene su ViewModel y pasa solo estado y acciones a la versión sin estado. */
@Composable
internal fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    developerMode: Boolean = false,
    viewModel: SettingsViewModel = koinViewModel(),
    languageSetting: AppLanguageSetting = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val language by languageSetting.language.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = state.settings,
        statuses = state.statuses,
        tests = state.tests,
        language = language,
        actions = SettingsActions(
            onBack = onBack,
            onAnswerConnectivityChecksChange = viewModel::setAnswerConnectivityChecks,
            onIntranetDirectChange = viewModel::setIntranetDirect,
            onVpnFallbackToProxyChange = viewModel::setVpnFallbackToProxy,
            onNameByIpChange = viewModel::setNameByIp,
            onTryRawIpChange = viewModel::setTryRawIp,
            onServiceEnabledChange = viewModel::setServiceEnabled,
            onServiceMove = viewModel::moveService,
            onServiceTest = viewModel::testService,
            onServiceRemove = viewModel::removeService,
            onAddService = viewModel::addService,
            onResetServices = viewModel::resetServices,
            onQuotaAlertChange = viewModel::setQuotaAlert,
            onQuotaCheckMinutesChange = viewModel::setQuotaCheckMinutes,
            onQuotaPauseChange = viewModel::setQuotaPause,
            onQuotaVerifyCertificateChange = viewModel::setQuotaVerifyCertificate,
            onQuotaServiceUrlChange = if (developerMode) viewModel::setQuotaServiceUrl else null,
            onLanguageChange = languageSetting::set,
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
    language: AppLanguage = AppLanguage.SYSTEM,
) {
    var adding by remember { mutableStateOf(false) }
    val spacing = ProxyUciTheme.spacing

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        UciTopBar(
            title = stringResource(Res.string.settings_title),
            subtitle = stringResource(Res.string.settings_subtitle),
            onBack = actions.onBack,
        )
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
                    title = stringResource(Res.string.settings_connection_title),
                    subtitle = stringResource(Res.string.settings_connection_subtitle),
                    icon = UciIcons.CheckCircle,
                ) {
                    UciSwitchRow(
                        title = stringResource(Res.string.settings_connectivity_checks_title),
                        subtitle = stringResource(Res.string.settings_connectivity_checks_subtitle),
                        checked = settings.answerConnectivityChecks,
                        onCheckedChange = actions.onAnswerConnectivityChecksChange,
                    )
                    UciDivider()
                    UciSwitchRow(
                        title = stringResource(Res.string.settings_intranet_direct_title),
                        subtitle = stringResource(Res.string.settings_intranet_direct_subtitle),
                        checked = settings.intranetDirect,
                        onCheckedChange = actions.onIntranetDirectChange,
                    )
                    UciDivider()
                    UciSwitchRow(
                        title = stringResource(Res.string.settings_vpn_fallback_title),
                        subtitle = stringResource(Res.string.settings_vpn_fallback_subtitle),
                        checked = settings.vpnFallbackToProxy,
                        onCheckedChange = actions.onVpnFallbackToProxyChange,
                    )
                }
                QuotaCard(settings.quota, actions)
                NameServicesCard(settings, statuses, tests, actions, onAdd = { adding = true })
                LanguageCard(language, actions.onLanguageChange)
                Spacer(Modifier.height(spacing.md))
            }
        }
    }

    if (adding) {
        AddServiceSheet(onDismiss = { adding = false }, onAdd = actions.onAddService)
    }
}

/** El idioma de la app. Cada idioma se nombra en sí mismo, para que quien no entiende el actual encuentre el suyo. */
@Composable
private fun LanguageCard(language: AppLanguage, onChange: (AppLanguage) -> Unit) {
    UciSectionCard(
        title = stringResource(Res.string.settings_language_title),
        subtitle = stringResource(Res.string.settings_language_subtitle),
        icon = UciIcons.Globe,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppLanguage.entries.forEach { option ->
                val label = when (option) {
                    AppLanguage.SYSTEM -> stringResource(Res.string.settings_language_system)
                    AppLanguage.SPANISH -> "Español"
                    AppLanguage.ENGLISH -> "English"
                }
                UciFilterChip(label, language == option, { onChange(option) })
            }
        }
    }
}

/** La cuota de navegación de la UCI: avisos, cada cuánto se consulta y la pausa al límite. */
@Composable
private fun QuotaCard(quota: QuotaSettings, actions: SettingsActions) {
    val c = ProxyUciTheme.colors
    UciSectionCard(
        title = stringResource(Res.string.settings_quota_title),
        subtitle = stringResource(Res.string.settings_quota_subtitle),
        icon = UciIcons.DataUsage,
    ) {
        Text(
            stringResource(Res.string.settings_quota_description),
            style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceMuted,
        )
        Text(stringResource(Res.string.settings_quota_alert_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuotaSettings.ALERT_THRESHOLDS.forEach { threshold ->
                val on = threshold in quota.alerts
                UciFilterChip(
                    stringResource(Res.string.settings_quota_alert_threshold, threshold),
                    on,
                    { actions.onQuotaAlertChange(threshold, !on) },
                )
            }
        }
        Text(stringResource(Res.string.settings_quota_check_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuotaSettings.CHECK_INTERVALS.forEach { minutes ->
                UciFilterChip(
                    pluralStringResource(Res.plurals.settings_quota_check_minutes, minutes, minutes),
                    quota.checkMinutes == minutes,
                    { actions.onQuotaCheckMinutesChange(minutes) },
                )
            }
        }
        UciDivider()
        UciSwitchRow(
            title = stringResource(Res.string.settings_quota_pause_title, QuotaSettings.PAUSE_THRESHOLD),
            subtitle = stringResource(Res.string.settings_quota_pause_subtitle, QuotaSettings.PAUSE_THRESHOLD - 5),
            checked = quota.pauseAtLimit,
            onCheckedChange = actions.onQuotaPauseChange,
        )
        UciDivider()
        UciSwitchRow(
            title = stringResource(Res.string.settings_quota_verify_title),
            subtitle = stringResource(Res.string.settings_quota_verify_subtitle),
            checked = quota.verifyCertificate,
            onCheckedChange = actions.onQuotaVerifyCertificateChange,
        )
        actions.onQuotaServiceUrlChange?.let { onChange ->
            UciDivider()
            // El texto vive aquí y se guarda a la vez: si el campo mostrara lo guardado, que vuelve con retraso, se
            // perderían letras al escribir rápido.
            var url by rememberSaveable { mutableStateOf(quota.serviceUrl.orEmpty()) }
            UciTextField(
                value = url,
                onValueChange = {
                    url = it
                    onChange(it)
                },
                label = stringResource(Res.string.settings_quota_service_url_label),
                placeholder = "https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php",
                supportingText = stringResource(Res.string.settings_quota_service_url_supporting),
                mono = true,
            )
        }
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
        title = stringResource(Res.string.settings_names_title),
        subtitle = stringResource(Res.string.settings_names_subtitle),
        icon = UciIcons.Dns,
        trailing = {
            UciSwitch(
                settings.nameByIp,
                actions.onNameByIpChange,
                contentDescription = stringResource(Res.string.settings_names_switch),
            )
        },
    ) {
        Text(
            stringResource(Res.string.settings_names_description),
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
            title = stringResource(Res.string.settings_try_raw_ip_title),
            subtitle = stringResource(Res.string.settings_try_raw_ip_subtitle),
            checked = settings.tryRawIp,
            onCheckedChange = actions.onTryRawIpChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UciButton(
                stringResource(Res.string.settings_add_service),
                onAdd,
                variant = UciButtonVariant.Secondary,
                icon = UciIcons.Add,
                modifier = Modifier.weight(1f),
            )
            UciButton(
                stringResource(Res.string.settings_reset_services),
                actions.onResetServices,
                variant = UciButtonVariant.Text,
                icon = UciIcons.Refresh,
            )
        }
        UciInlineAlert(
            stringResource(Res.string.settings_names_warning),
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
            val useDescription = stringResource(Res.string.settings_service_use, service.name)
            UciSwitch(
                checked = service.enabled,
                onCheckedChange = { actions.onServiceEnabledChange(service.id, it) },
                modifier = Modifier.semantics { contentDescription = useDescription },
            )
        }
        // La prueba que se acaba de hacer manda sobre lo anotado de usos anteriores.
        val (line, tone) = test?.message() ?: status?.message(nowMillis)
            ?: (stringResource(Res.string.settings_service_no_data) to UciTone.Neutral)
        Text(line, style = MaterialTheme.typography.bodySmall, color = if (tone == UciTone.Neutral) ProxyUciTheme.colors.onSurfaceFaint else tone.colors().content)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UciButton(
                stringResource(if (test == ServiceTestUi.Running) Res.string.settings_service_testing else Res.string.settings_service_test),
                { actions.onServiceTest(service.id) },
                variant = UciButtonVariant.Text,
                icon = UciIcons.Speed,
                loading = test == ServiceTestUi.Running,
            )
            if (service.builtIn) {
                UciStatusPill(stringResource(Res.string.settings_service_built_in))
            } else {
                UciButton(
                    stringResource(Res.string.settings_service_remove),
                    { actions.onServiceRemove(service.id) },
                    variant = UciButtonVariant.Text,
                    icon = UciIcons.Delete,
                )
            }
        }
    }
}

@Composable
private fun NameServiceStatus.message(nowMillis: Long): Pair<String, UciTone> {
    val ago = timeAgo(atMillis, nowMillis)
    return when (outcome) {
        NameOutcome.WORKED -> when (ago) {
            TimeAgo.Seconds -> stringResource(Res.string.settings_status_worked_seconds)
            is TimeAgo.Minutes -> pluralStringResource(Res.plurals.settings_status_worked_minutes, ago.count, ago.count)
            is TimeAgo.Hours -> pluralStringResource(Res.plurals.settings_status_worked_hours, ago.count, ago.count)
            is TimeAgo.Days -> pluralStringResource(Res.plurals.settings_status_worked_days, ago.count, ago.count)
        } to UciTone.Success
        NameOutcome.REFUSED -> when (ago) {
            TimeAgo.Seconds -> stringResource(Res.string.settings_status_refused_seconds)
            is TimeAgo.Minutes -> pluralStringResource(Res.plurals.settings_status_refused_minutes, ago.count, ago.count)
            is TimeAgo.Hours -> pluralStringResource(Res.plurals.settings_status_refused_hours, ago.count, ago.count)
            is TimeAgo.Days -> pluralStringResource(Res.plurals.settings_status_refused_days, ago.count, ago.count)
        } to UciTone.Danger
    }
}

@Composable
private fun ServiceTestUi.message(): Pair<String, UciTone> = when (this) {
    ServiceTestUi.Running -> stringResource(Res.string.settings_test_running) to UciTone.Active
    ServiceTestUi.NoProfile -> stringResource(Res.string.settings_test_no_profile) to UciTone.Warning
    // El mismo texto que cuando falla el túnel por lo mismo.
    ServiceTestUi.LocalNetworkDenied -> stringResource(Res.string.error_tunnel_local_network_denied) to UciTone.Warning
    is ServiceTestUi.Done -> when (val result = result) {
        NameServiceTestResult.Works -> stringResource(Res.string.settings_test_works) to UciTone.Success
        is NameServiceTestResult.Refused -> stringResource(Res.string.settings_test_refused, result.detail) to UciTone.Danger
        NameServiceTestResult.ProxyAuth -> stringResource(Res.string.settings_test_proxy_auth) to UciTone.Warning
        is NameServiceTestResult.Unreachable -> stringResource(Res.string.settings_test_unreachable, stringResource(result.kind.proxyReason)) to UciTone.Danger
        NameServiceTestResult.Timeout -> stringResource(Res.string.error_proxy_test_timeout) to UciTone.Warning
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

    UciBottomSheet(stringResource(Res.string.settings_add_service), onDismiss = onDismiss) {
        UciTextField(
            value = name,
            onValueChange = { name = it; errors = errors - ServiceError.NAME_EMPTY },
            label = stringResource(Res.string.settings_add_name_label),
            placeholder = stringResource(Res.string.settings_add_name_placeholder),
            error = ServiceError.NAME_EMPTY.takeIf { it in errors }?.let { stringResource(it.message) },
        )
        UciTextField(
            value = template,
            onValueChange = { template = it; errors = errors - TEMPLATE_ERRORS },
            label = stringResource(Res.string.settings_add_template_label),
            placeholder = "{ip-}.mi-dominio.com",
            mono = true,
            supportingText = stringResource(Res.string.settings_add_template_supporting),
            error = errors.firstOrNull { it in TEMPLATE_ERRORS }?.let { stringResource(it.message) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        UciInlineAlert(
            stringResource(Res.string.settings_add_warning),
            tone = UciTone.Warning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            UciButton(stringResource(Res.string.settings_add_cancel), onDismiss, variant = UciButtonVariant.Text)
            UciButton(stringResource(Res.string.settings_add_confirm), {
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

/** Por qué no vale el servicio, para mostrarlo bajo el campo que lo causa. */
private val ServiceError.message: StringResource
    get() = when (this) {
        ServiceError.NAME_EMPTY -> Res.string.settings_error_name_empty
        ServiceError.NO_PLACEHOLDER -> Res.string.settings_error_no_placeholder
        ServiceError.INVALID_HOST -> Res.string.settings_error_invalid_host
        ServiceError.LOCAL_DOMAIN -> Res.string.settings_error_local_domain
        ServiceError.DUPLICATE -> Res.string.settings_error_duplicate
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
