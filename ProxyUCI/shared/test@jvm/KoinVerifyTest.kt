package org.uci.proxy

import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.uci.proxy.di.appModules
import kotlin.test.Test

/** Comprobación estática del grafo: detecta constructores con dependencias sin enlazar. */
class KoinVerifyTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `app modules have no missing definitions`() {
        module { includes(appModules) }.verify(
            // Parámetros que los módulos pasan a mano al construir, no tipos que Koin deba resolver.
            extraTypes = listOf(CoroutineDispatcher::class, Function1::class),
        )
    }
}
