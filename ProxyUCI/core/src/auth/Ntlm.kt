package org.uci.proxy.core.auth

/*
 * Mensajes NTLMSSP con respuesta NTLMv2 (MS-NLMP). Port de NtlmAuth.cs de SuperProxyWin,
 * que está probado contra el proxy Squid de la UCI.
 */

class NtlmFormatException(message: String) : Exception(message)

/** Usuario separado de su dominio. Acepta `DOMINIO\usuario`, `usuario@dominio` o solo `usuario`. */
data class NtlmUser(val username: String, val domain: String?) {
    companion object {
        fun parse(raw: String): NtlmUser = when {
            '\\' in raw -> NtlmUser(username = raw.substringAfter('\\'), domain = raw.substringBefore('\\'))
            '@' in raw -> NtlmUser(username = raw.substringBefore('@'), domain = raw.substringAfter('@'))
            else -> NtlmUser(username = raw, domain = null)
        }
    }
}

/** Lo que interesa del mensaje Type 2 (Challenge) que envía el servidor. */
class NtlmChallenge(
    val serverChallenge: ByteArray,
    /** Nombre del dominio o servidor que anuncia el proxy; vacío si no lo envía. */
    val targetName: String,
    /** Bloque de pares AV, que el cliente debe devolver intacto dentro de su respuesta. */
    val targetInfo: ByteArray,
    /** MsvAvTimestamp en formato FILETIME, si el servidor lo incluye. */
    val serverTimestamp: Long?,
)

object NtlmMessages {
    private val SIGNATURE = byteArrayOf('N'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(),
        'S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 0)

    private const val NEGOTIATE_FLAGS =
        0x00000001 or // NTLMSSP_NEGOTIATE_UNICODE
            0x00000004 or // NTLMSSP_REQUEST_TARGET
            0x00000200 or // NTLMSSP_NEGOTIATE_NTLM
            0x00008000 or // NTLMSSP_NEGOTIATE_ALWAYS_SIGN
            0x00080000 or // NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY
            0x20000000 or // NTLMSSP_NEGOTIATE_128
            0x80000000.toInt() // NTLMSSP_NEGOTIATE_56

    private const val FLAG_TARGET_INFO = 0x00800000
    private const val AV_EOL = 0
    private const val AV_TIMESTAMP = 7
    private const val TYPE3_HEADER_SIZE = 64

    /** Type 1 (Negotiate): abre la negociación, sin dominio ni estación. */
    fun type1(): ByteArray {
        val message = ByteArray(32)
        SIGNATURE.copyInto(message)
        message.writeIntLe(8, 1)
        message.writeIntLe(12, NEGOTIATE_FLAGS)
        return message
    }

    fun parseType2(message: ByteArray): NtlmChallenge {
        if (message.size < 32 || !message.copyOf(8).contentEquals(SIGNATURE) || message.readIntLe(8) != 2) {
            throw NtlmFormatException("El mensaje NTLM Type 2 no es válido")
        }
        val flags = message.readIntLe(20)
        val targetName = message.securityBuffer(12)?.let { it.decodeUtf16Le(0, it.size) }.orEmpty()
        val targetInfo = if (message.size >= 48 && flags and FLAG_TARGET_INFO != 0) {
            message.securityBuffer(40) ?: ByteArray(0)
        } else {
            ByteArray(0)
        }
        return NtlmChallenge(
            serverChallenge = message.copyOfRange(24, 32),
            targetName = targetName,
            targetInfo = targetInfo,
            serverTimestamp = findTimestamp(targetInfo),
        )
    }

    /**
     * Type 3 (Authenticate) con respuesta NTLMv2.
     *
     * @param clientChallenge 8 bytes aleatorios.
     * @param clientFileTime hora actual en formato FILETIME; solo se usa si el servidor no envió la suya.
     */
    fun type3(
        challenge: NtlmChallenge,
        user: NtlmUser,
        password: String,
        clientChallenge: ByteArray,
        clientFileTime: Long,
        workstation: String,
    ): ByteArray {
        require(clientChallenge.size == 8) { "El desafío del cliente debe tener 8 bytes" }
        val domain = user.domain ?: challenge.targetName

        // MS-NLMP 3.1.5.1.2: si el servidor envía MsvAvTimestamp, el cliente debe usar esa hora
        // y mandar la respuesta LM a ceros.
        val fileTime = challenge.serverTimestamp ?: clientFileTime
        val responseKey = ntlmV2ResponseKey(user.username, domain, password)
        val ntResponse = ntlmV2Response(responseKey, challenge.serverChallenge, clientChallenge, fileTime, challenge.targetInfo)
        val lmResponse = if (challenge.serverTimestamp != null) {
            ByteArray(24)
        } else {
            lmV2Response(responseKey, challenge.serverChallenge, clientChallenge)
        }

        val domainBytes = domain.encodeToUtf16Le()
        val userBytes = user.username.encodeToUtf16Le()
        val workstationBytes = workstation.encodeToUtf16Le()

        val domainOffset = TYPE3_HEADER_SIZE
        val userOffset = domainOffset + domainBytes.size
        val workstationOffset = userOffset + userBytes.size
        val lmOffset = workstationOffset + workstationBytes.size
        val ntOffset = lmOffset + lmResponse.size
        val end = ntOffset + ntResponse.size

        val message = ByteArray(end)
        SIGNATURE.copyInto(message)
        message.writeIntLe(8, 3)
        message.writeSecurityBuffer(12, lmResponse.size, lmOffset)
        message.writeSecurityBuffer(20, ntResponse.size, ntOffset)
        message.writeSecurityBuffer(28, domainBytes.size, domainOffset)
        message.writeSecurityBuffer(36, userBytes.size, userOffset)
        message.writeSecurityBuffer(44, workstationBytes.size, workstationOffset)
        message.writeSecurityBuffer(52, 0, end) // sin clave de sesión
        message.writeIntLe(60, NEGOTIATE_FLAGS)

        domainBytes.copyInto(message, domainOffset)
        userBytes.copyInto(message, userOffset)
        workstationBytes.copyInto(message, workstationOffset)
        lmResponse.copyInto(message, lmOffset)
        ntResponse.copyInto(message, ntOffset)
        return message
    }

    /** NTOWFv2: HMAC-MD5(MD4(contraseña), MAYÚSCULAS(usuario) + dominio). */
    internal fun ntlmV2ResponseKey(username: String, domain: String, password: String): ByteArray =
        hmacMd5(md4(password.encodeToUtf16Le()), (username.uppercase() + domain).encodeToUtf16Le())

    /** NTProofStr seguido del blob "temp" de MS-NLMP 3.3.2. */
    internal fun ntlmV2Response(
        responseKey: ByteArray,
        serverChallenge: ByteArray,
        clientChallenge: ByteArray,
        fileTime: Long,
        targetInfo: ByteArray,
    ): ByteArray {
        // Blob: RespType=1, HiRespType=1, 6 reservados, hora (8), desafío del cliente (8),
        // 4 reservados, pares AV, 4 reservados.
        val blob = ByteArray(28 + targetInfo.size + 4)
        blob[0] = 1
        blob[1] = 1
        blob.writeLongLe(8, fileTime)
        clientChallenge.copyInto(blob, 16)
        targetInfo.copyInto(blob, 28)

        val proof = hmacMd5(responseKey, serverChallenge + blob)
        return proof + blob
    }

    internal fun lmV2Response(responseKey: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray =
        hmacMd5(responseKey, serverChallenge + clientChallenge) + clientChallenge

    private fun findTimestamp(targetInfo: ByteArray): Long? {
        var position = 0
        while (position + 4 <= targetInfo.size) {
            val id = targetInfo.readShortLe(position)
            val length = targetInfo.readShortLe(position + 2)
            position += 4
            if (id == AV_EOL || position + length > targetInfo.size) return null
            if (id == AV_TIMESTAMP && length == 8) return targetInfo.readLongLe(position)
            position += length
        }
        return null
    }

    /** Lee un "security buffer" (longitud de 2 bytes en [offset], posición de 4 bytes en [offset]+4). */
    private fun ByteArray.securityBuffer(offset: Int): ByteArray? {
        val length = readShortLe(offset)
        val start = readIntLe(offset + 4)
        if (length == 0 || start < 0 || start.toLong() + length > size) return null
        return copyOfRange(start, start + length)
    }

    private fun ByteArray.writeSecurityBuffer(offset: Int, length: Int, bufferOffset: Int) {
        writeShortLe(offset, length)
        writeShortLe(offset + 2, length)
        writeIntLe(offset + 4, bufferOffset)
    }
}
