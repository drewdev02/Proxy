package org.uci.proxy.core.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.uci.proxy.core.port.DataProtectionException
import org.uci.proxy.core.port.DataProtector
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra con AES-GCM usando una clave que vive en Android Keystore y nunca sale de él.
 * Formato del resultado: [longitud del IV (1 byte)][IV][texto cifrado + etiqueta].
 */
class AndroidKeystoreProtector(private val keyAlias: String) : DataProtector {
    override fun protect(plain: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    } catch (e: GeneralSecurityException) {
        throw DataProtectionException("No se pudo cifrar con Android Keystore", e)
    }

    override fun unprotect(protectedData: ByteArray): ByteArray {
        val ivLength = protectedData.firstOrNull()?.toInt() ?: throw DataProtectionException("Dato cifrado vacío")
        val payloadStart = 1 + ivLength
        if (ivLength <= 0 || protectedData.size <= payloadStart) throw DataProtectionException("Dato cifrado corrupto")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = GCMParameterSpec(TAG_BITS, protectedData, 1, ivLength)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), iv)
            cipher.doFinal(protectedData, payloadStart, protectedData.size - payloadStart)
        } catch (e: GeneralSecurityException) {
            throw DataProtectionException("No se pudo descifrar con Android Keystore", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
