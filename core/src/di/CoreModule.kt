package org.uci.proxy.core.di

import io.ktor.network.selector.SelectorManager
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.uci.proxy.core.log.LogBuffer
import org.uci.proxy.core.net.KtorTcpConnector
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.profile.AppDispatchers
import org.uci.proxy.core.profile.IdGenerator
import org.uci.proxy.core.profile.ProfileRepository
import org.uci.proxy.core.profile.ProfileValidator
import org.uci.proxy.core.profile.RandomIdGenerator
import org.uci.proxy.core.profile.StoredProfileRepository
import org.uci.proxy.core.proxy.BasicAuthScheme
import org.uci.proxy.core.proxy.ConnectingProxyTester
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.NtlmAuthScheme
import org.uci.proxy.core.proxy.ConnectingNameServiceTester
import org.uci.proxy.core.proxy.NameServiceTester
import org.uci.proxy.core.proxy.ProxyTarget
import org.uci.proxy.core.proxy.ProxyTester
import org.uci.proxy.core.proxy.Socks5Connector
import org.uci.proxy.core.settings.IpNameServiceValidator
import org.uci.proxy.core.settings.NameServiceStatusRepository
import org.uci.proxy.core.profile.ActiveProfileStore
import org.uci.proxy.core.profile.StoredActiveProfileStore
import org.uci.proxy.core.settings.SettingsRepository
import org.uci.proxy.core.settings.StoredNameServiceStatusRepository
import org.uci.proxy.core.settings.StoredSettingsRepository
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
    single<IdGenerator> { RandomIdGenerator() }
    singleOf(::ProfileValidator)
    singleOf(::StoredProfileRepository) { bind<ProfileRepository>() }
    singleOf(::StoredSettingsRepository) { bind<SettingsRepository>() }
    // El perfil elegido en «Conexión», recordado entre sesiones.
    singleOf(::StoredActiveProfileStore) { bind<ActiveProfileStore>() }
    // Cómo le fue a cada servicio de nombres para IP públicas, para mostrarlo en Ajustes.
    single<NameServiceStatusRepository> { StoredNameServiceStatusRepository(get(), get()) }
    singleOf(::IpNameServiceValidator)
    single<NameServiceTester> { ConnectingNameServiceTester(listOf(get<HttpConnectConnector>(), get<Socks5Connector>())) }

    single { SelectorManager(get<AppDispatchers>().background) }
    singleOf(::KtorTcpConnector) { bind<TcpConnector>() }

    singleOf(::NtlmAuthScheme)
    singleOf(::BasicAuthScheme)
    // El orden es la preferencia: NTLM antes que Basic, que manda la contraseña en claro.
    single { HttpConnectConnector(get(), schemes = listOf(get<NtlmAuthScheme>(), get<BasicAuthScheme>())) }
    singleOf(::Socks5Connector)
    single<ProxyTester> {
        ConnectingProxyTester(
            connectors = listOf(get<HttpConnectConnector>(), get<Socks5Connector>()),
            target = PROXY_TEST_TARGET,
            timeout = PROXY_TEST_TIMEOUT,
        )
    }
}
