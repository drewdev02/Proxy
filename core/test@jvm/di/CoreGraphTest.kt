package org.uci.proxy.core.di

import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.core.proxy.HttpConnectConnector
import org.uci.proxy.core.proxy.ProxyConnector
import org.uci.proxy.core.proxy.Socks5Connector
import org.uci.proxy.core.port.NameServiceReporter
import org.uci.proxy.core.port.NameServiceStatusRepository
import org.uci.proxy.core.vpn.CountryEngineCatalog
import org.uci.proxy.core.vpn.CountryEngines
import org.uci.proxy.core.vpn.EngineCatalog
import org.uci.proxy.core.vpn.OpenVpnEngine
import org.uci.proxy.core.vpn.ProxyDialer
import org.uci.proxy.core.vpn.tailscale.TailscaleBridge
import org.uci.proxy.core.vpn.tailscale.TailscaleEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * El grafo de un proceso que ejecuta el túnel (Android, el motor de escritorio): core y túnel juntos. Cada pieza que
 * comparten se crea una sola vez.
 */
class CoreGraphTest {
    /** Una librería de Tailscale que dice estar, para que el motor se ofrezca; nunca se llama. */
    private object PresentBridge : TailscaleBridge by TailscaleBridge.Unavailable {
        override val isAvailable = true
    }

    private val apps = mutableListOf<KoinApplication>()

    // Koin guarda los singletons dentro de cada `module`, y los módulos son globales: sin cerrar, una prueba vería los
    // objetos que creó la anterior.
    @AfterTest
    fun closeKoin() = apps.forEach { it.close() }

    private fun graph(vararg extra: Module): Koin = koinApplication {
        modules(
            listOf(
                platformModule,
                coreModule,
                tunnelModule,
                module {
                    // Nada de disco ni llavero reales.
                    single<KeyValueStore> { InMemoryKeyValueStore() }
                    single<SecretStore> { InMemorySecretStore() }
                    single<TailscaleBridge> { PresentBridge }
                },
            ) + extra,
        )
    }.also { apps += it }.koin

    @Test
    fun `the catalog and the tunnel share one Tailscale engine`() {
        val koin = graph()
        val engine = koin.get<TailscaleEngine>()

        assertSame(engine, (koin.get<CountryEngineCatalog>() as EngineCatalog).tailscale)
        assertSame(engine, koin.get<CountryEngines>()[CountryEngineKind.TAILSCALE])
        assertEquals(listOf(CountryEngineKind.OPENVPN, CountryEngineKind.TAILSCALE), koin.get<CountryEngineCatalog>().available)
    }

    @Test
    fun `every country engine dials through the same ProxyDialer`() {
        val koin = graph()
        val dialer = koin.get<ProxyDialer>()

        assertSame(dialer, koin.get<TailscaleEngine>().dialer)
        assertSame(dialer, koin.get<OpenVpnEngine>().dialer)
    }

    @Test
    fun `the proxy connectors are registered once, HTTP before SOCKS5`() {
        val koin = graph()
        val connectors = koin.get<List<ProxyConnector>>(ProxyConnectors)

        assertEquals(listOf(koin.get<HttpConnectConnector>(), koin.get<Socks5Connector>()), connectors)
        assertSame(connectors, koin.get<List<ProxyConnector>>(ProxyConnectors))
    }

    @Test
    fun `by default the dialer reports name services to this process's repository`() {
        val koin = graph()

        assertSame(koin.get<NameServiceStatusRepository>(), koin.get<ProxyDialer>().reporter)
    }

    @Test
    fun `a replaced reporter (the desktop engine's forwarder) reaches the dialer`() {
        val forwarder = NameServiceReporter { _, _ -> }
        val koin = graph(module { single<NameServiceReporter> { forwarder } })

        assertSame(forwarder, koin.get<ProxyDialer>().reporter)
    }
}
