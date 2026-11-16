package org.uci.proxy

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.di.appModules
import org.uci.proxy.home.HomeViewModel
import kotlin.test.Test
import kotlin.test.assertTrue

/** Corre en cada target: el grafo de la interfaz se resuelve con los módulos reales de esa plataforma. */
class AppModulesTest {
    private object SilentLogger : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = Unit
    }

    // Solo se sustituye el registro: en las pruebas locales de Android, android.util.Log es un stub que lanza.
    private val testOverrides = module { single<Logger> { SilentLogger } }

    @Test
    fun `HomeViewModel resolves from the app modules`() {
        val koin = koinApplication { modules(appModules + testOverrides) }.koin

        val viewModel = koin.get<HomeViewModel>()

        assertTrue(viewModel.uiState.platformName.isNotBlank())
    }
}
