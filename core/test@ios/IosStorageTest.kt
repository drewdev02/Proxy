package org.uci.proxy.core

import org.uci.proxy.core.platform.UserDefaultsKeyValueStore
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * KeychainSecretStore no se prueba aquí: el ejecutable de pruebas corre sin firmar y el llavero
 * le responde errSecMissingEntitlement (-34018). Se verifica con la app instalada en el simulador.
 */
class IosStorageTest {
    private val suiteName = "org.uci.proxy.tests.${NSUUID().UUIDString}"
    private val defaults = NSUserDefaults(suiteName = suiteName)
    private val keyValueStore = UserDefaultsKeyValueStore(defaults)

    @AfterTest
    fun cleanUp() {
        defaults.removePersistentDomainForName(suiteName)
    }

    @Test
    fun `user defaults store reads back what it wrote`() {
        keyValueStore.putString("clave", "valor con ñ")

        assertEquals("valor con ñ", keyValueStore.getString("clave"))
    }

    @Test
    fun `user defaults store removes values`() {
        keyValueStore.putString("clave", "valor")

        keyValueStore.remove("clave")

        assertNull(keyValueStore.getString("clave"))
    }

    @Test
    fun `an unknown key reads as null`() {
        assertNull(keyValueStore.getString("no-existe"))
    }
}
