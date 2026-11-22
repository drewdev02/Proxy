package org.uci.proxy.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.uci.proxy.theme.ProxyUciTheme
import org.uci.proxy.ui.components.UciBottomNavBar
import org.uci.proxy.ui.components.UciBottomSheet
import org.uci.proxy.ui.components.UciButton
import org.uci.proxy.ui.components.UciButtonVariant
import org.uci.proxy.ui.components.UciCard
import org.uci.proxy.ui.components.UciCheckboxRow
import org.uci.proxy.ui.components.UciConfirmDialog
import org.uci.proxy.ui.components.UciDivider
import org.uci.proxy.ui.components.UciEmptyState
import org.uci.proxy.ui.components.UciFileField
import org.uci.proxy.ui.components.UciFilterChip
import org.uci.proxy.ui.components.UciIconBox
import org.uci.proxy.ui.components.UciIconButton
import org.uci.proxy.ui.components.UciInlineAlert
import org.uci.proxy.ui.components.UciLatencyBadge
import org.uci.proxy.ui.components.UciLogEntryCard
import org.uci.proxy.ui.components.UciLogTag
import org.uci.proxy.ui.components.UciMetricTile
import org.uci.proxy.ui.components.UciQuotaRing
import org.uci.proxy.ui.components.UciProportionBar
import org.uci.proxy.ui.components.UciBarSegment
import org.uci.proxy.ui.components.UciBar
import org.uci.proxy.ui.components.UciBarChart
import org.uci.proxy.ui.components.UciNavItem
import org.uci.proxy.ui.components.UciNavigationRail
import org.uci.proxy.ui.components.UciPasswordField
import org.uci.proxy.ui.components.UciPowerButton
import org.uci.proxy.ui.components.UciPowerState
import org.uci.proxy.ui.components.UciProfileRow
import org.uci.proxy.ui.components.UciReorderableList
import org.uci.proxy.ui.components.UciSearchField
import org.uci.proxy.ui.components.UciSectionCard
import org.uci.proxy.ui.components.UciSectionHeader
import org.uci.proxy.ui.components.UciSegmentedControl
import org.uci.proxy.ui.components.UciSettingRow
import org.uci.proxy.ui.components.UciSparkline
import org.uci.proxy.ui.components.UciStatusDot
import org.uci.proxy.ui.components.UciStatusPill
import org.uci.proxy.ui.components.UciSwitch
import org.uci.proxy.ui.components.UciSwitchRow
import org.uci.proxy.ui.components.UciTextField
import org.uci.proxy.ui.components.UciTone
import org.uci.proxy.ui.components.UciTopBar
import org.uci.proxy.ui.icons.UciIcons

/*
 * Catálogo de componentes: todos los componentes en todos sus estados, con datos de ejemplo.
 * Solo se ofrece en modo desarrollo. Sirve para revisarlos a la vez en Android, macOS y Windows, y las pruebas
 * de capturas (CatalogSnapshotTest) dibujan cada sección para detectar cambios de aspecto no buscados.
 */

/** Una sección del catálogo. El orden es el de la pantalla. */
enum class CatalogSection(val title: String, val content: @Composable ColumnScope.() -> Unit) {
    Foundations("Colores y tipografía", { FoundationsSection() }),
    Containers("Contenedores", { ContainersSection() }),
    Buttons("Botones", { ButtonsSection() }),
    Power("Botón de conexión", { PowerSection() }),
    Status("Estado", { StatusSection() }),
    Forms("Formularios", { FormsSection() }),
    Lists("Listas", { ListsSection() }),
    Navigation("Navegación", { NavigationSection() }),
    Data("Datos", { DataSection() }),
}

@Composable
fun ComponentCatalogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { UciTopBar("Catálogo de componentes", subtitle = "Solo en modo desarrollo", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = ProxyUciTheme.spacing.screenMargin,
                vertical = ProxyUciTheme.spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.xl),
        ) {
            items(CatalogSection.entries, key = { it.name }) { CatalogSectionView(it) }
        }
    }
}

/** Una sección con su título. Se usa en la pantalla y en las pruebas de capturas. */
@Composable
fun CatalogSectionView(section: CatalogSection, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md)) {
        Text(section.title, style = MaterialTheme.typography.headlineSmall)
        section.content(this)
    }
}

@Composable
private fun Sub(text: String) = UciSectionHeader(text)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundationsSection() {
    val c = ProxyUciTheme.colors
    val scheme = MaterialTheme.colorScheme
    Sub("Superficies")
    Swatches(listOf("Fondo" to c.canvas, "Tarjeta" to c.card, "Borde" to c.cardBorder, "Control" to c.control, "Borde ctrl." to c.controlBorder))
    Sub("Significado")
    Swatches(
        listOf(
            "Activo" to c.active, "Conectado" to c.connected, "Otro País" to c.routing, "Reposo" to c.idle,
            "Aviso" to c.warning, "Error" to c.danger, "Primario" to scheme.primary,
        ),
    )
    Sub("Tipografía")
    val t = MaterialTheme.typography
    val m = ProxyUciTheme.mono
    listOf<Pair<String, TextStyle>>(
        "Display 32" to t.displayMedium,
        "Headline 20" to t.headlineSmall,
        "Title 16" to t.titleMedium,
        "Body 14 — texto normal de la interfaz" to t.bodyMedium,
        "Label 12" to t.labelMedium,
    ).forEach { (text, style) -> Text(text, style = style) }
    Text("4.82", style = m.metricLarge)
    Text("proxy.uci.cu:8080 · 38 ms", style = m.metricSmall)
    Text("10.0.0.0/8 → DIRECT", style = m.code)
    Text("NTLMV2 · TUNNEL", style = m.badge, color = c.onSurfaceMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
                Box(
                    Modifier.size(48.dp)
                        .background(color, MaterialTheme.shapes.small)
                        .border(1.dp, ProxyUciTheme.colors.controlBorder, MaterialTheme.shapes.small),
                )
                Text(name, style = MaterialTheme.typography.labelSmall, color = ProxyUciTheme.colors.onSurfaceMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContainersSection() {
    Sub("Tarjetas por tono")
    UciCard(Modifier.fillMaxWidth()) { Text("Normal") }
    UciCard(Modifier.fillMaxWidth(), tone = UciTone.Success) { Text("Conectado") }
    UciCard(Modifier.fillMaxWidth(), tone = UciTone.Routing, onClick = {}) { Text("Otro País (se puede pulsar)") }
    Sub("Tarjeta de sección")
    var on by remember { mutableStateOf(true) }
    UciSectionCard(
        title = "Otro País",
        subtitle = "Canadá · OpenVPN tras el proxy",
        icon = UciIcons.Globe,
        tone = UciTone.Routing,
        trailing = { UciSwitch(on, { on = it }, contentDescription = "Activar Otro País") },
    ) {
        Text(
            "El tráfico pasa primero por el proxy para autenticarse y luego sale por la VPN.",
            style = MaterialTheme.typography.bodyMedium,
            color = ProxyUciTheme.colors.onSurfaceMuted,
        )
    }
    Sub("Caja de icono")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UciTone.entries.forEach { UciIconBox(UciIcons.Shield, tone = it) }
    }
}

@Composable
private fun ButtonsSection() {
    Sub("Variantes")
    UciButtonVariant.entries.forEach { variant ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            UciButton(variant.name, {}, variant = variant, icon = UciIcons.Refresh)
            UciButton("Inactivo", {}, variant = variant, enabled = false)
        }
    }
    Sub("Cargando y ancho completo")
    UciButton("Probando conexión", {}, loading = true, modifier = Modifier.fillMaxWidth())
    UciButton("Guardar cambios", {}, icon = UciIcons.Check, modifier = Modifier.fillMaxWidth())
    Sub("Solo icono")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        UciIconButton(UciIcons.Swap, "Cambiar perfil", {})
        UciIconButton(UciIcons.Copy, "Copiar", {}, tone = UciTone.Active)
        UciIconButton(UciIcons.Delete, "Borrar", {}, tone = UciTone.Danger)
        UciIconButton(UciIcons.Terminal, "Registros", {}, bordered = false)
        UciIconButton(UciIcons.Edit, "Editar", {}, enabled = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerSection() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            UciPowerState.Idle to "Conectar",
            UciPowerState.Connecting to "Conectando",
            UciPowerState.Connected to "Conectado",
            UciPowerState.Error to "Reintentar",
        ).forEach { (state, label) -> UciPowerButton(state, label, {}, diameter = 110.dp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSection() {
    Sub("Etiquetas")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UciStatusPill("Direct")
        UciStatusPill("Tunnel", tone = UciTone.Active)
        UciStatusPill("NTLMv2", tone = UciTone.Success, icon = UciIcons.Lock)
        UciStatusPill("Otro País", tone = UciTone.Routing)
        UciStatusPill("Aislado", tone = UciTone.Warning)
        UciStatusPill("Falla", tone = UciTone.Danger)
        UciStatusPill("HTTP 200", tone = UciTone.Success, pill = false)
        UciStatusPill("DNS:INTERCEPT", tone = UciTone.Routing, pill = false)
    }
    Sub("Puntos y latencia")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        UciStatusDot(UciTone.Success)
        UciStatusDot(UciTone.Active, pulsing = true)
        UciStatusDot(UciTone.Neutral)
        UciStatusDot(UciTone.Danger)
        UciLatencyBadge(38)
        UciLatencyBadge(120)
        UciLatencyBadge(420)
        UciLatencyBadge(null)
    }
    Sub("Filtros")
    var selected by remember { mutableStateOf("Todos") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple("Todos", 142, null), Triple("DNS", 68, UciTone.Routing), Triple("Proxy", 45, UciTone.Active), Triple("Errores", 3, UciTone.Danger))
            .forEach { (label, count, dot) -> UciFilterChip(label, selected == label, { selected = label }, count = count, dot = dot) }
    }
    Sub("Avisos")
    UciInlineAlert("Los servicios de nombres se probarán al conectar.")
    UciInlineAlert("Conectado por sslip.io (42 ms).", tone = UciTone.Success)
    UciInlineAlert("nip.io falló 3 veces y queda aislado 10 minutos.", tone = UciTone.Warning, title = "Servicio aislado", onDismiss = {})
    UciInlineAlert(
        "El proxy rechazó el usuario o la contraseña.",
        tone = UciTone.Danger,
        title = "No se pudo conectar",
        action = { UciButton("Reintentar", {}, variant = UciButtonVariant.Text) },
    )
}

@Composable
private fun FormsSection() {
    var host by remember { mutableStateOf("proxy.uci.cu") }
    var port by remember { mutableStateOf("80a") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("secreto") }
    var query by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("NTLMv2") }
    var mode by remember { mutableStateOf("Inclusivo") }
    var s1 by remember { mutableStateOf(true) }
    var s2 by remember { mutableStateOf(false) }
    var cidr1 by remember { mutableStateOf(true) }
    var cidr2 by remember { mutableStateOf(false) }

    Sub("Campos")
    UciTextField(host, { host = it }, "Servidor", leadingIcon = UciIcons.Dns, mono = true, supportingText = "Nombre o IP del proxy")
    UciTextField(port, { port = it }, "Puerto", mono = true, error = "Debe ser un número entre 1 y 65535")
    UciTextField(user, { user = it }, "Usuario", placeholder = "nombre.apellido", leadingIcon = UciIcons.Person)
    UciPasswordField(pass, { pass = it }, "Contraseña", supportingText = "Se guarda en el almacén seguro del sistema")
    UciTextField("solo lectura", {}, "Inactivo", enabled = false)
    UciSearchField(query, { query = it }, "Buscar dominio, IP o etiqueta")
    Sub("Selector")
    UciSegmentedControl(listOf("NTLMv2", "Basic", "Sin auth"), auth, { auth = it }, label = { it })
    UciSegmentedControl(
        listOf("Inclusivo", "Exclusivo"),
        mode,
        { mode = it },
        label = { if (it == "Inclusivo") "Solo seleccionadas" else "Todas excepto" },
        description = { "Modo $it" },
    )
    Sub("Interruptores y casillas")
    UciCard(Modifier.fillMaxWidth()) {
        UciSwitchRow("Responder comprobaciones de red", s1, { s1 = it }, subtitle = "El túnel contesta en local", icon = UciIcons.CheckCircle)
        UciDivider()
        UciSwitchRow("Protección de fugas de DNS", s2, { s2 = it }, icon = UciIcons.Shield)
        UciDivider()
        UciSwitchRow("No disponible aquí", false, {}, enabled = false)
        UciDivider()
        UciCheckboxRow("10.0.0.0/8", cidr1, { cidr1 = it }, subtitle = "Redes de la universidad", mono = true)
        UciDivider()
        UciCheckboxRow("192.168.0.0/16", cidr2, { cidr2 = it }, subtitle = "Redes domésticas", mono = true)
    }
    Sub("Archivo")
    UciFileField("canada_udp.ovpn", {}, detail = "14,2 KB · importado")
    UciFileField(null, {})
}

@Composable
private fun ListsSection() {
    Sub("Filas de ajuste")
    UciCard(Modifier.fillMaxWidth()) {
        UciSettingRow("Servicios de nombres", subtitle = "sslip.io, nip.io", icon = UciIcons.Dns, onClick = {})
        UciDivider()
        UciSettingRow("Versión", icon = UciIcons.Info) { Text("0.9.4", style = ProxyUciTheme.mono.metricSmall, color = ProxyUciTheme.colors.onSurfaceMuted) }
    }
    Sub("Perfiles")
    UciProfileRow(
        name = "UCI Docente",
        address = "proxy.uci.cu:8080",
        status = UciTone.Success,
        tone = UciTone.Success,
        onClick = {},
        tags = {
            UciStatusPill("HTTP")
            UciStatusPill("NTLMv2", tone = UciTone.Success)
            UciStatusPill("Otro País", tone = UciTone.Routing)
        },
        trailing = { UciIconButton(UciIcons.Edit, "Editar perfil", {}, bordered = false) },
    )
    UciProfileRow(
        name = "Residencia",
        address = "10.0.0.1:3128",
        status = UciTone.Active,
        statusPulsing = true,
        onClick = {},
        tags = { UciStatusPill("SOCKS5") },
    )
    Sub("Lista ordenable")
    val services = remember { mutableStateListOf("sslip.io", "nip.io", "propio.example.cu") }
    UciReorderableList(services, onMove = { from, to -> services.add(to, services.removeAt(from)) }) { name ->
        Text(name, style = ProxyUciTheme.mono.metricSmall, modifier = Modifier.weight(1f))
        UciLatencyBadge(if (name == "sslip.io") 42 else if (name == "nip.io") 58 else null)
    }
    Sub("Vacío")
    UciCard(Modifier.fillMaxWidth()) {
        UciEmptyState(
            title = "Aún no hay perfiles",
            icon = UciIcons.Badge,
            message = "Añade el proxy de tu facultad para empezar.",
            action = { UciButton("Añadir perfil", {}, icon = UciIcons.Add) },
        )
    }
}

private enum class Dest { Connection, Logs, Profiles, Settings }

@Composable
private fun NavigationSection() {
    val items = listOf(
        UciNavItem(Dest.Connection, "Conexión", UciIcons.Shield),
        UciNavItem(Dest.Logs, "Registros", UciIcons.Logs, badge = 12),
        UciNavItem(Dest.Profiles, "Perfiles", UciIcons.Badge),
        UciNavItem(Dest.Settings, "Ajustes", UciIcons.Tune),
    )
    var dest by remember { mutableStateOf(Dest.Connection) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }

    Sub("Cabecera")
    UciCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        UciTopBar("Editar perfil", subtitle = "UCI Docente", onBack = {}, actions = { UciStatusPill("Tunnel", tone = UciTone.Success) })
    }
    Sub("Barra inferior (móvil)")
    UciBottomNavBar(items, dest, { dest = it })
    Sub("Barra lateral (tableta y escritorio)")
    Box(Modifier.height(320.dp).border(1.dp, ProxyUciTheme.colors.cardBorder, MaterialTheme.shapes.medium)) {
        UciNavigationRail(items, dest, { dest = it })
    }
    Sub("Diálogo y hoja inferior")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UciButton("Borrar perfil", { dialog = true }, variant = UciButtonVariant.Danger)
        UciButton("Elegir país", { sheet = true }, variant = UciButtonVariant.Secondary)
    }
    if (dialog) {
        UciConfirmDialog(
            title = "¿Borrar «UCI Docente»?",
            message = "Se borran también su contraseña y su VPN guardadas.",
            confirmLabel = "Borrar",
            destructive = true,
            onConfirm = { dialog = false },
            onDismiss = { dialog = false },
        )
    }
    if (sheet) {
        UciBottomSheet("Elegir país", onDismiss = { sheet = false }) {
            listOf("Canadá", "Países Bajos", "Japón").forEach { UciSettingRow(it, icon = UciIcons.Globe, onClick = { sheet = false }) }
        }
    }
}

@Composable
private fun DataSection() {
    Sub("Cifras")
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
        UciMetricTile(
            "Descarga", "4.8", Modifier.weight(1f), unit = "MB/s", icon = UciIcons.ArrowDown, accent = UciTone.Active,
            chart = { UciSparkline(listOf(1f, 3f, 2f, 5f, 4f, 6f, 4.8f)) },
        )
        UciMetricTile(
            "Subida", "820", Modifier.weight(1f), unit = "KB/s", icon = UciIcons.ArrowUp, accent = UciTone.Success,
            chart = { UciSparkline(listOf(2f, 2f, 3f, 2.5f, 4f, 3f, 3.5f), color = ProxyUciTheme.colors.connected) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.gutterSmall)) {
        UciMetricTile("Conexiones", "34", Modifier.weight(1f), icon = UciIcons.Hub, caption = "TCP activas")
        UciMetricTile("Sesión", "02:45", Modifier.weight(1f), unit = "h", icon = UciIcons.Schedule, caption = "desde las 14:30")
    }
    Sub("Consumo")
    val colors = ProxyUciTheme.colors
    UciBarChart(
        bars = listOf("Ju" to 3f, "Vi" to 5f, "Sá" to 4f, "Do" to 1f, "Lu" to 0f, "Ma" to 4.5f, "Mi" to 3.2f).mapIndexed { i, (day, value) ->
            UciBar(day, listOf(UciBarSegment(value * 0.7f, colors.active), UciBarSegment(value * 0.3f, colors.routing)), highlighted = i == 6)
        },
    )
    UciProportionBar(0.7f)
    UciProportionBar(0.3f, color = colors.routing)
    Row(horizontalArrangement = Arrangement.spacedBy(ProxyUciTheme.spacing.md)) {
        UciQuotaRing(0.71f, color = colors.connected) { Text("594 MB", style = ProxyUciTheme.mono.metricSmall) }
        UciQuotaRing(0.92f, color = colors.danger) { Text("158 MB", style = ProxyUciTheme.mono.metricSmall) }
    }
    Sub("Registro")
    UciLogEntryCard(
        time = "14:32:05.124",
        title = "github.com:443",
        tags = listOf(UciLogTag("Relay:connect", UciTone.Active), UciLogTag("HTTP 200", UciTone.Success)),
        message = "Túnel HTTP CONNECT abierto",
        details = listOf("Ruta" to "10.0.0.45:52410 → proxy.uci.cu:8080", "Auth" to "NTLMv2"),
        trailing = "32 ms",
        trailingTone = UciTone.Success,
        icon = UciIcons.Lock,
    )
    UciLogEntryCard(
        time = "14:31:58.112",
        title = "198-51-100-24.sslip.io",
        tags = listOf(UciLogTag("DNS:resolver", UciTone.Routing), UciLogTag("Fallback", UciTone.Warning)),
        message = "Resuelto por el servicio de nombres",
        icon = UciIcons.Dns,
    )
    UciLogEntryCard(
        time = "14:31:45.890",
        title = "proxy.uci.cu:8080",
        tags = listOf(UciLogTag("Proxy:auth", UciTone.Danger)),
        message = "407: el proxy rechazó las credenciales",
        trailing = "ERROR",
        trailingTone = UciTone.Danger,
        icon = UciIcons.Error,
    )
}
