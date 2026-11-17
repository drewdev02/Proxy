package org.uci.proxy.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.uci.proxy.core.port.SecretStore
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Guarda cada secreto como una contraseña genérica en el llavero de iOS. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretStore(private val service: String) : SecretStore {
    override fun put(key: String, value: String) {
        remove(key)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("No se pudo codificar el secreto '$key'")
        val status = withQuery(key, mapOf(kSecValueData to data)) { SecItemAdd(it, null) }
        check(status == errSecSuccess) { "El llavero rechazó guardar '$key' (OSStatus $status)" }
    }

    override fun get(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(key, mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) {
            SecItemCopyMatching(it, result.ptr)
        }
        when (status) {
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)
                ?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
            errSecItemNotFound -> null
            else -> error("El llavero no pudo leer '$key' (OSStatus $status)")
        }
    }

    override fun remove(key: String) {
        val status = withQuery(key, emptyMap()) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "El llavero no pudo borrar '$key' (OSStatus $status)"
        }
    }

    /**
     * Construye la consulta del llavero para [key], la pasa a [block] y libera todo al terminar.
     * Los valores de [extra] pueden ser objetos de Foundation (se puentean) o constantes de CoreFoundation.
     */
    private fun <T> withQuery(key: String, extra: Map<CFTypeRef?, Any?>, block: (CFMutableDictionaryRef?) -> T): T {
        val retained = mutableListOf<CFTypeRef?>()
        fun bridge(value: Any?): CFTypeRef? = CFBridgingRetain(value).also(retained::add)

        val query = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, bridge(service))
            CFDictionaryAddValue(query, kSecAttrAccount, bridge(key))
            extra.forEach { (attribute, value) ->
                CFDictionaryAddValue(query, attribute, if (value is NSData) bridge(value) else value as CFTypeRef?)
            }
            return block(query)
        } finally {
            retained.forEach { CFRelease(it) }
            CFRelease(query)
        }
    }
}
