package org.uci.proxy.core.port

/** Preferencias no sensibles, persistentes entre arranques. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Datos sensibles, guardados con la protección que ofrezca la plataforma.
 * Las claves solo usan caracteres [A-Za-z0-9._-].
 */
interface SecretStore {
    fun put(key: String, value: String)

    /** Devuelve null si no existe o si ya no se puede descifrar. */
    fun get(key: String): String?

    fun remove(key: String)
}

/** Cifra y descifra con una clave que custodia el sistema operativo. */
interface DataProtector {
    fun protect(plain: ByteArray): ByteArray

    /** @throws DataProtectionException si los datos no se pueden descifrar. */
    fun unprotect(protectedData: ByteArray): ByteArray
}

class DataProtectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
