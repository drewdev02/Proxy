package org.uci.proxy.core

import org.uci.proxy.core.platform.JvmPlatformInfo
import org.uci.proxy.core.platform.MacKeychainSecretStore
import org.uci.proxy.core.port.PlatformKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Prueba de integración contra el llavero real. Solo actúa en macOS; en otros sistemas no hace nada.
 * Usa un servicio propio de pruebas y borra lo que crea.
 */
class MacKeychainSecretStoreTest {
    private val onMac = JvmPlatformInfo.fromSystemProperties().kind == PlatformKind.MACOS
    private val store = MacKeychainSecretStore("org.uci.proxy.tests")
    private val key = "test.${System.nanoTime()}.password"

    @AfterTest
    fun cleanUp() {
        if (onMac) store.remove(key)
    }

    @Test
    fun `a stored secret is read back including special characters`() {
        if (!onMac) return
        val secret = "contraseña ñ \"comillas\" 'simples' \\ %s \$HOME ; rm"

        store.put(key, secret)

        assertEquals(secret, store.get(key))
    }

    @Test
    fun `putting again replaces the value`() {
        if (!onMac) return
        store.put(key, "vieja")

        store.put(key, "nueva")

        assertEquals("nueva", store.get(key))
    }

    @Test
    fun `an unknown key reads as null`() {
        if (!onMac) return

        assertNull(store.get(key))
    }

    @Test
    fun `remove deletes the secret and tolerates a missing one`() {
        if (!onMac) return
        store.put(key, "secreta")

        store.remove(key)
        store.remove(key)

        assertNull(store.get(key))
    }

    @Test
    fun `a value longer than one keychain command fits, like an ovpn file`() {
        if (!onMac) return
        // Unos 13 KB en Base64: varias órdenes de `security -i`.
        val ovpn = (1..400).joinToString("\n") { "línea $it del perfil ñ <tls-crypt> ABCDEF0123456789" }

        store.put(key, ovpn)

        assertEquals(ovpn, store.get(key))
    }

    @Test
    fun `a long value can be replaced by a short one and removed without leftovers`() {
        if (!onMac) return
        val probe = MacKeychainSecretStore("org.uci.proxy.tests")
        store.put(key, "x".repeat(9000))

        store.put(key, "corta")
        assertEquals("corta", store.get(key))
        assertNull(probe.get("$key.part1"), "los trozos del valor largo se borran")

        store.put(key, "y".repeat(9000))
        store.remove(key)
        assertNull(store.get(key))
        assertNull(probe.get("$key.part1"))
    }

    @Test
    fun `keys that would need escaping are rejected`() {
        assertFailsWith<IllegalArgumentException> { store.put("clave con \"comillas\"", "x") }
        assertFailsWith<IllegalArgumentException> { store.get("a b") }
    }
}
