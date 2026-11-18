package org.uci.proxy.core.vpn

enum class HmacAlgorithm { MD5, SHA1, SHA256 }

/** Las primitivas criptográficas que necesita el protocolo. Cada plataforma aporta las suyas. */
interface OpenVpnCrypto {
    fun hmac(algorithm: HmacAlgorithm, key: ByteArray, vararg parts: ByteArray): ByteArray

    /** AES-256 en modo contador, para envolver el canal de control con `tls-crypt`. */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

    /** @return el texto cifrado y la etiqueta de autenticación por separado (OpenVPN envía la etiqueta primero). */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): SealedData

    /** @return el texto en claro, o null si la etiqueta no coincide. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray?
}

class SealedData(val ciphertext: ByteArray, val tag: ByteArray)

/**
 * El PRF de TLS 1.0 (MD5 y SHA-1 combinados), con el que OpenVPN deriva sus claves del intercambio «key method 2».
 * No es el de TLS 1.2: OpenVPN conserva el antiguo.
 */
internal fun tls1Prf(crypto: OpenVpnCrypto, secret: ByteArray, label: String, seed: ByteArray, length: Int): ByteArray {
    val labelSeed = label.encodeToByteArray() + seed
    val half = (secret.size + 1) / 2
    val s1 = secret.copyOfRange(0, half)
    val s2 = secret.copyOfRange(secret.size - half, secret.size)
    val md5 = pHash(crypto, HmacAlgorithm.MD5, s1, labelSeed, length)
    val sha1 = pHash(crypto, HmacAlgorithm.SHA1, s2, labelSeed, length)
    return ByteArray(length) { (md5[it].toInt() xor sha1[it].toInt()).toByte() }
}

private fun pHash(crypto: OpenVpnCrypto, algorithm: HmacAlgorithm, secret: ByteArray, seed: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    var written = 0
    var a = seed
    while (written < length) {
        a = crypto.hmac(algorithm, secret, a)
        val block = crypto.hmac(algorithm, secret, a, seed)
        val take = minOf(block.size, length - written)
        block.copyInto(out, written, 0, take)
        written += take
    }
    return out
}

/** Las claves de un sentido del canal de datos con cifrado AEAD. */
internal class DataKeys(val cipherKey: ByteArray, val implicitIv: ByteArray)

internal class DataChannelKeys(val send: DataKeys, val receive: DataKeys)

/**
 * Deriva las claves del canal de datos del intercambio de claves, como hace OpenVPN en `generate_key_expansion`.
 * Del resultado de 256 bytes salen dos bloques de 128 (64 de clave de cifrado y 64 de HMAC); con AEAD solo se usan
 * los primeros 32 bytes de cifrado y los primeros 8 del HMAC, como vector implícito.
 */
internal fun deriveDataKeys(
    crypto: OpenVpnCrypto,
    preMaster: ByteArray,
    clientRandom1: ByteArray,
    clientRandom2: ByteArray,
    serverRandom1: ByteArray,
    serverRandom2: ByteArray,
    clientSessionId: ByteArray,
    serverSessionId: ByteArray,
    cipherKeyBytes: Int,
): DataChannelKeys {
    val master = tls1Prf(crypto, preMaster, "OpenVPN master secret", clientRandom1 + serverRandom1, 48)
    val expansion = tls1Prf(
        crypto,
        master,
        "OpenVPN key expansion",
        clientRandom2 + serverRandom2 + clientSessionId + serverSessionId,
        256,
    )
    fun block(index: Int) = DataKeys(
        cipherKey = expansion.copyOfRange(index * 128, index * 128 + cipherKeyBytes),
        implicitIv = expansion.copyOfRange(index * 128 + 64, index * 128 + 64 + 8),
    )
    // Al revés que con tls-crypt: en el canal de datos el cliente cifra con el primer bloque y descifra con el segundo
    // (comprobado contra openvpn 2.7: con el orden contrario el servidor rechaza los paquetes).
    return DataChannelKeys(send = block(0), receive = block(1))
}
