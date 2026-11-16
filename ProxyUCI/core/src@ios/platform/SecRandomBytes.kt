package org.uci.proxy.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.uci.proxy.core.port.RandomBytes
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

class SecRandomBytes : RandomBytes {
    @OptIn(ExperimentalForeignApi::class)
    override fun next(size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
        check(status == errSecSuccess) { "El sistema no entregó bytes aleatorios (OSStatus $status)" }
        return bytes
    }
}
