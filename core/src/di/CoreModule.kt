package org.uci.proxy.core.di

import io.ktor.network.selector.SelectorManager
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.traffic.TrafficMonitor
import org.uci.proxy.core.traffic.TrafficStore
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.common.AppDispatchers
import org.uci.proxy.core.common.IdGenerator
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.common.RandomIdGenerator
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.proxy.BasicAuthScheme
import org.uci.proxy.core.proxy.ConnectingProxyTester
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.NtlmAuthScheme
import org.uci.proxy.core.proxy.ConnectingNameServiceTester
import org.uci.proxy.core.proxy.NameServiceTester
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.proxy.ProxyTester
import org.uci.proxy.core.proxy.Socks5Connector
import org.uci.proxy.core.settings.IpNameServiceValidator
import org.uci.proxy.core.port.NameServiceReporter
import org.uci.proxy.core.port.NameServiceStatusRepository
import org.uci.proxy.core.profile.ActiveProfileStore
import org.uci.proxy.core.profile.StoredActiveProfileStore
import org.uci.proxy.core.port.SettingsRepository
import org.uci.proxy.core.data.StoredNameServiceStatusRepository
import org.uci.proxy.core.data.StoredSettingsRepository
import org.uci.proxy.core.vpn.CountryEngineCatalog
import org.uci.proxy.core.vpn.EngineCatalog
import org.uci.proxy.core.vpn.ProxyDialer
import org.uci.proxy.core.vpn.tailscale.TailscaleEngine
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

// Destino del túnel de prueba: el mismo servicio de comprobación de conectividad que usa Android.
private val PROXY_TEST_TARGET = ProxyTarget("connectivitycheck.gstatic.com", 443)
private val PROXY_TEST_TIMEOUT = 15.seconds

/** Lógica común. Depende de los puertos y de los [AppDispatchers] que enlaza [platformModule]. */
val coreModule = module {
    single<Clock> { Clock.System }
    // Los últimos mensajes de registro, para la pantalla «Registro». Cada plataforma envuelve su registrador con él.
    single { LogBuffer() }
    // El tráfico de la sesión: lo publica el motor (o, en escritorio, la ventana con lo que le cuenta el motor).
    single { TrafficStore() } bind TrafficMonitor::class
    single<IdGenerator> { RandomIdGenerator() }
    singleOf(::ProfileValidator)
    singleOf(::StoredProfileRepository) { bind<ProfileRepository>() }
    singleOf(::StoredSettingsRepository) { bind<SettingsRepository>() }
    // El perfil elegido en «Conexión», recordado entre sesiones.
    singleOf(::StoredActiveProfileStore) { bind<ActiveProfileStore>() }
    // Cómo le fue a cada servicio de nombres para IP públicas, para mostrarlo en Ajustes.
    single<NameServiceStatusRepository> { StoredNameServiceStatusRepository(get(), get()) }
    singleOf(::IpNameServiceValidator)
    // Quien se entera de qué servicio de nombres funcionó: por defecto, el repositorio de este mismo proceso. El motor de
    // escritorio, que corre aparte, lo sustituye por uno que avisa a la ventana.
    single<NameServiceReporter> { get<NameServiceStatusRepository>() }
    single<NameServiceTester> { ConnectingNameServiceTester(get(ProxyConnectors)) }

    single { SelectorManager(get<AppDispatchers>().background) }
    singleOf(::KtorTcpConnector) { bind<TcpConnector>() }

    singleOf(::NtlmAuthScheme)
    singleOf(::BasicAuthScheme)
    // El orden es la preferencia: NTLM antes que Basic, que manda la contraseña en claro.
    single { HttpConnectConnector(get(), schemes = listOf(get<NtlmAuthScheme>(), get<BasicAuthScheme>())) }
    singleOf(::Socks5Connector)
    // Todos los protocolos de proxy; quien abre conexiones por el proxy del perfil elige de aquí el suyo.
    single<List<ProxyConnector>>(ProxyConnectors) { listOf(get<HttpConnectConnector>(), get<Socks5Connector>()) }

    // Cómo llegan los motores de Otro País a sus servidores: por el proxy del perfil, todos igual.
    single { ProxyDialer(connectors = get(ProxyConnectors), logger = get(), reporter = get()) }
    // Un solo motor Tailscale por proceso: lo comparten la búsqueda de nodos (interfaz) y el túnel. No guarda estado
    // propio; cada sesión lleva el suyo.
    single {
        TailscaleEngine(
            bridge = tailscaleBridge(),
            dialer = get(),
            selector = get(),
            random = get(),
            logger = get(),
            io = get<AppDispatchers>().background,
        )
    }
    // Los motores de Otro País que puede ofrecer la interfaz, y la búsqueda de nodos de Tailscale.
    single<CountryEngineCatalog> { EngineCatalog(openVpnAvailable = openVpnAvailable(), tailscale = get()) }
    single<ProxyTester> {
        ConnectingProxyTester(
            connectors = get(ProxyConnectors),
            target = PROXY_TEST_TARGET,
            timeout = PROXY_TEST_TIMEOUT,
        )
    }
}
