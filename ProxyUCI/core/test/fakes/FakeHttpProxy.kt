package org.uci.proxy.core.fakes

import org.uci.proxy.core.auth.NtlmMessages
import org.uci.proxy.core.auth.decodeUtf16Le
import org.uci.proxy.core.auth.encodeToUtf16Le
import org.uci.proxy.core.auth.readIntLe
import org.uci.proxy.core.auth.readLongLe
import org.uci.proxy.core.auth.readShortLe
import org.uci.proxy.core.auth.writeIntLe
import org.uci.proxy.core.auth.writeShortLe
import kotlin.io.encoding.Base64

/**
 * Proxy HTTP simulado que entiende `CONNECT` y exige, según se configure, Basic y/o NTLM.
 * Tras responder 200 hace eco de lo que recibe, como si fuera el destino.
 */
class FakeHttpProxy(
    /** Esquemas que anuncia en `Proxy-Authenticate`. Vacío: no exige autenticación. */
    private val offered: List<String> = emptyList(),
    private val username: String = "andrey",
    private val domain: String = "UCI",
    private val password: String = "secreta",
    /** Si es true, cada 407 lleva `Connection: close` y el servidor cierra. */
    private val closeAfter407: Boolean = false,
    private val chunked407: Boolean = false,
    /** Estado distinto de 200 con el que responde a una petición ya autorizada. */
    private val statusWhenAuthorized: String = "200 Connection established",
    /** Bytes que el destino envía pegados a la respuesta 200. */
    private val earlyTunnelData: String = "",
) {
    val connector = FakeTcpConnector { ProxyConnectionState().connection }

    /** Valor de `Proxy-Authorization` de cada petición recibida, en orden; null si no la traía. */
    val authorizations = mutableListOf<String?>()
    val requestLines = mutableListOf<String>()

    private val serverChallenge = ByteArray(8) { (it + 1).toByte() }

    private inner class ProxyConnectionState {
        private var tunnelOpen = false
        private var challengeSent = false

        val connection = FakeConnection { bytes -> if (tunnelOpen) bytes else handleRequest(bytes.decodeToString()) }

        private fun handleRequest(request: String): ByteArray? {
            val lines = request.split("\r\n")
            requestLines += lines.first()
            val authorization = lines.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            authorizations += authorization

            return when {
                offered.isEmpty() || isAuthorized(authorization) -> {
                    if (statusWhenAuthorized.startsWith("200")) tunnelOpen = true
                    response(statusWhenAuthorized, body = earlyTunnelData.takeIf { tunnelOpen }.orEmpty(), raw = tunnelOpen)
                }
                authorization?.startsWith("NTLM ") == true && !challengeSent && "NTLM" in offered -> {
                    challengeSent = true
                    val type2 = buildType2(serverChallenge, domain)
                    challenge(listOf("NTLM ${Base64.encode(type2)}"))
                }
                else -> challenge(offered)
            }
        }

        private fun isAuthorized(authorization: String?): Boolean = when {
            authorization == null -> false
            authorization.startsWith("Basic ") && "Basic" in offered ->
                Base64.decode(authorization.substring(6)).decodeToString() == "$username:$password"
            authorization.startsWith("NTLM ") && challengeSent ->
                isValidType3(Base64.decode(authorization.substring(5)))
            else -> false
        }
    }

    private fun challenge(authenticate: List<String>): ByteArray? {
        val headers = authenticate.map { "Proxy-Authenticate: $it" } +
            if (closeAfter407) listOf("Connection: close") else listOf("Proxy-Connection: keep-alive")
        return response("407 Proxy Authentication Required", headers, body = "<html>Acceso denegado</html>")
    }

    private fun response(status: String, headers: List<String> = emptyList(), body: String = "", raw: Boolean = false): ByteArray {
        val head = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { head.append(it).append("\r\n") }
        return when {
            // Túnel abierto: sin Content-Length; lo que sigue ya son datos del destino.
            raw -> (head.append("\r\n").toString() + body).encodeToByteArray()
            chunked407 && status.startsWith("407") -> {
                head.append("Transfer-Encoding: chunked\r\n\r\n")
                val half = body.length / 2
                val chunks = listOf(body.substring(0, half), body.substring(half)).joinToString("") {
                    it.encodeToByteArray().size.toString(16) + "\r\n" + it + "\r\n"
                }
                (head.toString() + chunks + "0\r\n\r\n").encodeToByteArray()
            }
            else -> (head.append("Content-Length: ${body.encodeToByteArray().size}\r\n\r\n").toString() + body).encodeToByteArray()
        }
    }

    /** Comprueba la respuesta NTLMv2 recalculándola con la contraseña que el proxy conoce. */
    private fun isValidType3(message: ByteArray): Boolean {
        fun field(headerOffset: Int): ByteArray {
            val length = message.readShortLe(headerOffset)
            val start = message.readIntLe(headerOffset + 4)
            return message.copyOfRange(start, start + length)
        }
        val ntResponse = field(20)
        val sentDomain = field(28).let { it.decodeUtf16Le(0, it.size) }
        val sentUser = field(36).let { it.decodeUtf16Le(0, it.size) }
        if (!sentUser.equals(username, ignoreCase = true) || ntResponse.size < 16 + 32) return false

        val blob = ntResponse.copyOfRange(16, ntResponse.size)
        val expected = NtlmMessages.ntlmV2Response(
            responseKey = NtlmMessages.ntlmV2ResponseKey(username, sentDomain, password),
            serverChallenge = serverChallenge,
            clientChallenge = blob.copyOfRange(16, 24),
            fileTime = blob.readLongLe(8),
            targetInfo = blob.copyOfRange(28, blob.size - 4),
        )
        return expected.contentEquals(ntResponse)
    }
}

/** Mensaje NTLM Type 2 mínimo: desafío y nombre de destino, sin pares AV. */
fun buildType2(serverChallenge: ByteArray, targetName: String): ByteArray {
    val name = targetName.encodeToUtf16Le()
    val message = ByteArray(48 + name.size)
    "NTLMSSP".encodeToByteArray().copyInto(message)
    message.writeIntLe(8, 2)
    message.writeShortLe(12, name.size)
    message.writeShortLe(14, name.size)
    message.writeIntLe(16, 48)
    message.writeIntLe(20, 0x00000001)
    serverChallenge.copyInto(message, 24)
    name.copyInto(message, 48)
    return message
}
