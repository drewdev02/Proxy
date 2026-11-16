package org.uci.proxy

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
        module { includes(appModules) }.verify()
    }
}
