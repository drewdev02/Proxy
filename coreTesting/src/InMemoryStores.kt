package org.uci.proxy.core.fakes

import org.uci.proxy.core.port.DataProtectionException
import org.uci.proxy.core.port.DataProtector
import org.uci.proxy.core.port.KeyValueStore
import org.uci.proxy.core.model.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.SecretStore

class InMemoryKeyValueStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class InMemorySecretStore : SecretStore {
    val secrets = mutableMapOf<String, String>()
    var failOnPut = false

    override fun put(key: String, value: String) {
        check(!failOnPut) { "almacén seguro no disponible" }
        secrets[key] = value
    }

    override fun get(key: String): String? = secrets[key]

    override fun remove(key: String) {
        secrets.remove(key)
    }
}

class RecordingLogger : Logger {
    val entries = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, tag: String, message: String) {
        entries += level to message
    }
}

/** "Cifra" invirtiendo los bytes: basta para comprobar que el almacén no guarda el texto en claro. */
class ReversingProtector : DataProtector {
    var failOnUnprotect = false

    override fun protect(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun unprotect(protectedData: ByteArray): ByteArray {
        if (failOnUnprotect) throw DataProtectionException("clave perdida")
        return protectedData.reversedArray()
    }
}
