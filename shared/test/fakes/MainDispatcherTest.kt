package org.uci.proxy.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Base para pruebas de view models: `viewModelScope` corre en Main, que en pruebas no existe. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainDispatcherTest {
    /** Quien «mira» los uiState durante la prueba; se crea en el primer uso, ya con el Main de pruebas instalado. */
    private var subscriptions: CoroutineScope? = null

    @BeforeTest
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        subscriptions?.cancel()
        subscriptions = null
        Dispatchers.resetMain()
    }

    /**
     * Mantiene a alguien suscrito a este estado durante la prueba, como haría la pantalla. Hace falta porque los
     * ViewModels usan `SharingStarted.WhileSubscribed`: sin suscriptor, el estado no se recalcula.
     */
    protected fun <T> StateFlow<T>.keepSubscribed(): StateFlow<T> {
        val scope = subscriptions ?: CoroutineScope(Dispatchers.Main + SupervisorJob()).also { subscriptions = it }
        scope.launch { collect {} }
        return this
    }
}
