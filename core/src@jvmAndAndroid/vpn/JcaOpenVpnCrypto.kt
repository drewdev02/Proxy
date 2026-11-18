package org.uci.proxy.core.vpn

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Las primitivas de [OpenVpnCrypto] con las clases estándar de Java, iguales en escritorio y en Android. */
class JcaOpenVpnCrypto : OpenVpnCrypto {
    override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, vararg parts: ByteArray): ByteArray {
        val name = when (algorithm) {
            HmacAlgorithm.MD5 -> "HmacMD5"
            HmacAlgorithm.SHA1 -> "HmacSHA1"
            HmacAlgorithm.SHA256 -> "HmacSHA256"
        }
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(key, name))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    override fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): SealedData {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        // Java devuelve el texto cifrado seguido de la etiqueta.
        return SealedData(out.copyOfRange(0, out.size - TAG_BYTES), out.copyOfRange(out.size - TAG_BYTES, out.size))
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext + tag)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }

    private companion object {
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
    }
}
