package org.uci.proxy.core.profile

import org.uci.proxy.core.common.AppDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import org.uci.proxy.core.fakes.InMemorySecretStore
import org.uci.proxy.core.fakes.RecordingLogger
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.port.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoredProfileRepositoryTest {
    private val keyValueStore = InMemoryKeyValueStore()
    private val secretStore = InMemorySecretStore()
    private val logger = RecordingLogger()
    private val dispatchers = AppDispatchers(background = UnconfinedTestDispatcher())

    private fun newRepository() = StoredProfileRepository(keyValueStore, secretStore, dispatchers, logger)

    private val uci = ProxyProfile("a1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")
    private val home = ProxyProfile("b2", "Casa", "10.0.0.1", 1080, ProxyProtocol.SOCKS5, "")

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), newRepository().profiles.value)
    }

    @Test
    fun `saved profiles survive a restart`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")
        repository.save(home, "")

        val reopened = newRepository()

        assertEquals(listOf(uci, home), reopened.profiles.value)
        assertEquals("secreta", reopened.password(uci.id))
    }

    @Test
    fun `the password is never written to the plain store`() = runTest {
        newRepository().save(uci, "secreta")

        assertFalse(keyValueStore.values.values.any { "secreta" in it })
        assertEquals(listOf("secreta"), secretStore.secrets.values.toList())
    }

    @Test
    fun `saving an existing id replaces it in place`() = runTest {
        val repository = newRepository()
        repository.save(uci, "vieja")
        repository.save(home, "")

        repository.save(uci.copy(name = "UCI nuevo", port = 8080), "nueva")

        assertEquals(listOf(uci.copy(name = "UCI nuevo", port = 8080), home), repository.profiles.value)
        assertEquals("nueva", repository.password(uci.id))
    }

    @Test
    fun `saving with an empty password clears the stored one`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")

        repository.save(uci, "")

        assertEquals("", repository.password(uci.id))
        assertTrue(secretStore.secrets.isEmpty())
    }

    @Test
    fun `password of an unknown profile is empty`() = runTest {
        assertEquals("", newRepository().password("no-existe"))
    }

    @Test
    fun `delete removes the profile and its password`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta")
        repository.save(home, "otra")

        repository.delete(uci.id)

        assertEquals(listOf(home), repository.profiles.value)
        assertEquals(listOf("otra"), secretStore.secrets.values.toList())
        assertEquals(listOf(home), newRepository().profiles.value)
    }

    @Test
    fun `a failing secret store leaves the profile list untouched`() = runTest {
        val repository = newRepository()
        secretStore.failOnPut = true

        assertFailsWith<IllegalStateException> { repository.save(uci, "secreta") }

        assertEquals(emptyList(), repository.profiles.value)
        assertEquals(emptyList(), newRepository().profiles.value)
    }

    @Test
    fun `corrupt stored data is reported and treated as empty`() {
        keyValueStore.putString("profiles", "{esto no es una lista")

        val repository = newRepository()

        assertEquals(emptyList(), repository.profiles.value)
        assertEquals(listOf(LogLevel.ERROR), logger.entries.map { it.first })
    }

    private val withVpn = uci.copy(countryExit = CountryExit.OpenVpn("pais.ovpn", "vpnuser"))
    private val secrets = CountryExitSecrets.OpenVpn("client\n<tls-crypt>clave</tls-crypt>\n", "vpnpass")

    @Test
    fun `the VPN file and its password are kept in the secure store and survive a restart`() = runTest {
        newRepository().save(withVpn, "secreta", secrets)

        val reopened = newRepository()

        assertEquals(withVpn, reopened.profiles.value.single())
        assertEquals(secrets, reopened.exitSecrets(withVpn.id))
        assertFalse(keyValueStore.values.values.any { "tls-crypt" in it || "vpnpass" in it }, "nada de la VPN en el almacén sin cifrar")
    }

    @Test
    fun `a profile without VPN has no VPN secrets`() = runTest {
        val repository = newRepository()
        repository.save(uci, "secreta", secrets)

        assertEquals(null, repository.exitSecrets(uci.id), "sin VPN en el perfil no se guarda nada aunque se pasen secretos")
        assertTrue(secretStore.secrets.keys.none { "vpn" in it })
    }

    @Test
    fun `removing the VPN from a profile deletes its secrets`() = runTest {
        val repository = newRepository()
        repository.save(withVpn, "secreta", secrets)

        repository.save(uci, "secreta", null)

        assertEquals(null, repository.exitSecrets(uci.id))
        assertTrue(secretStore.secrets.keys.none { "vpn" in it })
    }

    @Test
    fun `an empty VPN password is not stored`() = runTest {
        val repository = newRepository()
        repository.save(withVpn, "secreta", secrets)

        repository.save(withVpn, "secreta", secrets.copy(password = ""))

        assertEquals(secrets.copy(password = ""), repository.exitSecrets(withVpn.id))
    }

    @Test
    fun `deleting a profile deletes its VPN secrets`() = runTest {
        val repository = newRepository()
        repository.save(withVpn, "secreta", secrets)

        repository.delete(withVpn.id)

        assertEquals(null, repository.exitSecrets(withVpn.id))
        assertTrue(secretStore.secrets.isEmpty())
    }

    @Test
    fun `profiles saved before the VPN existed are still read`() = runTest {
        keyValueStore.putString("profiles", """[{"id":"a1","name":"UCI","host":"proxy.uci.cu","port":3128,"protocol":"HTTP","username":"andrey"}]""")

        assertEquals(listOf(uci), newRepository().profiles.value)
    }

    @Test
    fun `profiles saved with an OpenVPN exit before the engines existed are read as OpenVPN`() = runTest {
        keyValueStore.putString(
            "profiles",
            """[{"id":"a1","name":"UCI","host":"proxy.uci.cu","port":3128,"protocol":"HTTP","username":"andrey",""" +
                """"vpn":{"fileName":"pais.ovpn","username":"vpnuser"}}]""",
        )

        assertEquals(listOf(withVpn), newRepository().profiles.value)
    }

    private val withTailscale = uci.copy(countryExit = CountryExit.Tailscale(exitNode = "nodo-ca", hostname = "portatil"))

    @Test
    fun `a Tailscale exit and its auth key survive a restart with the key only in the secure store`() = runTest {
        newRepository().save(withTailscale, "secreta", CountryExitSecrets.Tailscale("tskey-auth-abc"))

        val reopened = newRepository()

        assertEquals(withTailscale, reopened.profiles.value.single())
        assertEquals(CountryExitSecrets.Tailscale("tskey-auth-abc"), reopened.exitSecrets(withTailscale.id))
        assertFalse(keyValueStore.values.values.any { "tskey" in it }, "la clave no va al almacén sin cifrar")
        assertTrue(keyValueStore.values.values.any { "\"type\":\"tailscale\"" in it }, "el motor queda guardado")
    }

    @Test
    fun `changing the engine deletes the secrets of the previous one`() = runTest {
        val repository = newRepository()
        repository.save(withVpn, "secreta", secrets)

        repository.save(withTailscale, "secreta", CountryExitSecrets.Tailscale("tskey-auth-abc"))

        assertEquals(CountryExitSecrets.Tailscale("tskey-auth-abc"), repository.exitSecrets(withTailscale.id))
        assertTrue(secretStore.secrets.keys.none { "vpn" in it }, "ni el .ovpn ni su contraseña quedan guardados")
    }

    @Test
    fun `a Tailscale exit without a stored key still reconnects with the node identity`() = runTest {
        val repository = newRepository()
        repository.save(withTailscale, "secreta", CountryExitSecrets.Tailscale(""))

        assertEquals(CountryExitSecrets.Tailscale(""), repository.exitSecrets(withTailscale.id))
    }
}
