package org.uci.proxy.core

import org.koin.dsl.koinApplication
import org.uci.proxy.core.di.platformModule
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.PlatformInfo
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Corre en cada target: comprueba que el `actual` de esa plataforma enlaza todos los puertos. */
class PlatformModuleTest {
    private val koin = koinApplication { modules(platformModule) }.koin

    @Test
    fun `platform module binds PlatformInfo`() {
        val info = koin.get<PlatformInfo>()

        assertTrue(info.osVersion.isNotBlank())
    }

    @Test
    fun `platform module binds Logger`() {
        assertNotNull(koin.get<Logger>())
    }
}
