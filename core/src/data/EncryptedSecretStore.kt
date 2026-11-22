package org.uci.proxy.core.data

import org.uci.proxy.core.port.DataProtectionException
import org.uci.proxy.core.port.DataProtector
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.port.SecretStore
import kotlin.io.encoding.Base64

/**
 * [SecretStore] para plataformas sin un llavero propio: cifra con un [DataProtector] del sistema
 * y deja el texto cifrado en un [KeyValueStore]. Lo usan Android (Keystore) y Windows (DPAPI).
 */
class EncryptedSecretStore(
    private val storage: KeyValueStore,
    private val protector: DataProtector,
) : SecretStore {
    override fun put(key: String, value: String) {
        storage.putString(key, Base64.encode(protector.protect(value.encodeToByteArray())))
    }

    override fun get(key: String): String? {
        val stored = storage.getString(key) ?: return null
        return try {
            protector.unprotect(Base64.decode(stored)).decodeToString()
        } catch (_: DataProtectionException) {
            // La clave del sistema ya no existe (copia restaurada en otro equipo, por ejemplo).
            null
        } catch (_: IllegalArgumentException) {
            // El valor guardado no es Base64 válido.
            null
        }
    }

    override fun remove(key: String) {
        storage.remove(key)
    }
}
