package org.uci.proxy.core.vpn

private const val MESSAGE = "El modo Otro País no está disponible en esta plataforma"

/** Para iOS, que no tiene cliente VPN propio: así el resto del núcleo compila y resuelve sus dependencias. */
object UnavailableOpenVpnCrypto : OpenVpnCrypto {
    override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, vararg parts: ByteArray): ByteArray = throw OpenVpnException(MESSAGE)

    override fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = throw OpenVpnException(MESSAGE)

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): SealedData =
        throw OpenVpnException(MESSAGE)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray? =
        throw OpenVpnException(MESSAGE)
}

object UnavailableTlsSessionFactory : TlsSessionFactory {
    override fun create(caPem: String): TlsSession = throw OpenVpnException(MESSAGE)
}
