package org.uci.proxy.core.data

import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.ReversingProtector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EncryptedSecretStoreTest {
    private val storage = InMemoryKeyValueStore()
    private val protector = ReversingProtector()
    private val store = EncryptedSecretStore(storage, protector)

    @Test
    fun `a stored secret is read back`() {
        store.put("clave", "contraseña ñ %s \"comillas\"")

        assertEquals("contraseña ñ %s \"comillas\"", store.get("clave"))
    }

    @Test
    fun `the secret is not stored in clear text`() {
        store.put("clave", "secreta")

        assertFalse(storage.values.values.any { "secreta" in it })
    }

    @Test
    fun `an unknown key reads as null`() {
        assertNull(store.get("no-existe"))
    }

    @Test
    fun `a removed secret reads as null`() {
        store.put("clave", "secreta")

        store.remove("clave")

        assertNull(store.get("clave"))
    }

    @Test
    fun `a secret that can no longer be decrypted reads as null`() {
        store.put("clave", "secreta")
        protector.failOnUnprotect = true

        assertNull(store.get("clave"))
    }

    @Test
    fun `a stored value that is not valid Base64 reads as null`() {
        storage.putString("clave", "¡esto no es base64!")

        assertNull(store.get("clave"))
    }
}
