package org.uci.proxy

import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.uci.proxy.core.port.TunnelControl
import org.uci.proxy.core.port.TunnelMonitor
import org.uci.proxy.fakes.FakeTunnel
import org.uci.proxy.core.log.InMemoryLogHistory
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.LogHistory
import org.uci.proxy.core.port.LocalNetworkPermission
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore
import org.uci.proxy.di.appModules
import org.uci.proxy.fakes.FakeLocalNetworkPermission
import org.uci.proxy.fakes.FakeTrafficHistory
import org.uci.proxy.core.traffic.TrafficHistory
import org.uci.proxy.usage.UsageViewModel
import org.uci.proxy.fakes.InMemoryKeyValueStore
import org.uci.proxy.fakes.InMemorySecretStore
import org.uci.proxy.fakes.RecordingLogger
import org.uci.proxy.connection.ConnectionViewModel
import org.uci.proxy.logs.LogsViewModel
import org.uci.proxy.profile.ProfileEditorViewModel
import org.uci.proxy.profile.ProfileListViewModel
import org.uci.proxy.settings.SettingsViewModel
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
        // La base de datos real escribiría en el disco del equipo (y en Android pide un Context).
        single<LogHistory> { InMemoryLogHistory() }
        single<TrafficHistory> { FakeTrafficHistory() }
        single<LocalNetworkPermission> { FakeLocalNetworkPermission() }
        // En Android el túnel lo enlazan módulos de la propia app, que aquí no se cargan.
        single { FakeTunnel() } binds arrayOf(TunnelControl::class, TunnelMonitor::class)
    }
    private val koin = koinApplication { modules(appModules + testOverrides) }.koin

    @Test
    fun `profile view models resolve from the app modules`() {
        assertTrue(koin.get<ProfileListViewModel>().uiState.value.platformName.isNotEmpty())
        assertNotNull(koin.get<ProfileEditorViewModel>())
    }

    @Test
    fun `settings view model resolves from the app modules`() {
        assertNotNull(koin.get<SettingsViewModel>())
    }

    @Test
    fun `connection view model resolves from the app modules`() {
        assertNotNull(koin.get<ConnectionViewModel>())
    }

    @Test
    fun `usage view model resolves from the app modules`() {
        assertNotNull(koin.get<UsageViewModel>())
    }

    @Test
    fun `logs view model resolves from the app modules`() {
        assertNotNull(koin.get<LogsViewModel>())
    }
}
