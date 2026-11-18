package org.uci.proxy

import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.tunnel.TunnelViewModel
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.di.appModules
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.InMemoryKeyValueStore
import org.uci.proxy.fakes.InMemorySecretStore
import org.uci.proxy.fakes.RecordingLogger
import org.uci.proxy.home.HomeViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Corre en cada target: el grafo de la interfaz se resuelve con los módulos reales de esa plataforma. */
class AppModulesTest {
    // Se sustituye solo lo que toca el sistema: en las pruebas locales de Android no hay Context ni Logcat,
    // y en escritorio e iOS los almacenes reales escribirían en el disco y el llavero del equipo.
    private val testOverrides = module {
        single<Logger> { RecordingLogger() }
        single<KeyValueStore> { InMemoryKeyValueStore() }
        single<SecretStore> { InMemorySecretStore() }
        single<LocalNetworkPermission> { FakeLocalNetworkPermission() }
        // En Android el túnel lo enlazan módulos de la propia app, que aquí no se cargan.
        single { FakeTunnel() } binds arrayOf(TunnelControl::class, TunnelMonitor::class)
    }
    private val koin = koinApplication { modules(appModules + testOverrides) }.koin

    @Test
    fun `HomeViewModel resolves from the app modules`() {
        assertTrue(koin.get<HomeViewModel>().uiState.platformName.isNotBlank())
    }

    @Test
    fun `profile view models resolve from the app modules`() {
        assertNotNull(koin.get<ProfileListViewModel>())
        assertNotNull(koin.get<ProfileEditorViewModel>())
    }

    @Test
    fun `tunnel view model resolves from the app modules`() {
        assertNotNull(koin.get<TunnelViewModel>())
    }

    @Test
    fun `logs view model resolves from the app modules`() {
        assertNotNull(koin.get<LogsViewModel>())
    }
}
