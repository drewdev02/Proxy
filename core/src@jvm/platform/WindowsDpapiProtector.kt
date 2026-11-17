package org.uci.proxy.core.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import org.uci.proxy.core.port.DataProtectionException
import org.uci.proxy.core.port.DataProtector

/** Cifra con DPAPI: solo la misma cuenta de usuario de Windows puede descifrar. */
class WindowsDpapiProtector : DataProtector {
    override fun protect(plain: ByteArray): ByteArray = try {
        Crypt32Util.cryptProtectData(plain)
    } catch (e: Win32Exception) {
        throw DataProtectionException("No se pudo cifrar con DPAPI", e)
    }

    override fun unprotect(protectedData: ByteArray): ByteArray = try {
        Crypt32Util.cryptUnprotectData(protectedData)
    } catch (e: Win32Exception) {
        throw DataProtectionException("No se pudo descifrar con DPAPI", e)
    }
}
