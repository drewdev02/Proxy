package org.uci.proxy.core.platform

import org.uci.proxy.core.port.RandomBytes
import java.security.SecureRandom

class JavaSecureRandomBytes : RandomBytes {
    private val random = SecureRandom()

    override fun next(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
