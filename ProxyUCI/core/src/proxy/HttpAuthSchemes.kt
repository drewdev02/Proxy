package org.uci.proxy.core.proxy

import org.uci.proxy.core.auth.NtlmFormatException
import org.uci.proxy.core.auth.NtlmMessages
import org.uci.proxy.core.auth.NtlmUser
import org.uci.proxy.core.port.RandomBytes
import kotlin.io.encoding.Base64
import kotlin.time.Clock

data class ProxyCredentials(val username: String, val password: String)

/** Resultado de un esquema: null si el túnel quedó abierto, o el motivo del fallo. */
typealias AuthOutcome = ProxyFailure?

/**
 * Un esquema de autenticación de proxy HTTP. Añadir uno nuevo (Digest, Negotiate) es crear otra
 * implementación y registrarla; [HttpConnectConnector] no cambia.
 */
interface HttpProxyAuthScheme {
    /** Nombre tal como aparece en `Proxy-Authenticate`, por ejemplo "NTLM". */
    val name: String

    /**
     * Intenta abrir el túnel de [session] autenticando con [credentials].
     * Devuelve null si el proxy respondió 2xx.
     */
    suspend fun authenticate(session: HttpProxySession, credentials: ProxyCredentials): AuthOutcome
}

internal fun HttpResponseHead.toFailure(): ProxyFailure = when (statusCode) {
    407, 401 -> ProxyFailure.AuthRejected
    else -> ProxyFailure.Refused(statusLine)
}

class BasicAuthScheme : HttpProxyAuthScheme {
    override val name = "Basic"

    override suspend fun authenticate(session: HttpProxySession, credentials: ProxyCredentials): AuthOutcome {
        val token = Base64.encode("${credentials.username}:${credentials.password}".encodeToByteArray())
        val response = session.connect("Basic $token")
        return if (response.statusCode in 200..299) null else response.toFailure()
    }
}

class NtlmAuthScheme(
    private val randomBytes: RandomBytes,
    private val clock: Clock,
) : HttpProxyAuthScheme {
    override val name = "NTLM"

    override suspend fun authenticate(session: HttpProxySession, credentials: ProxyCredentials): AuthOutcome {
        val negotiate = session.connect("NTLM ${Base64.encode(NtlmMessages.type1())}")
        if (negotiate.statusCode in 200..299) return null
        if (negotiate.statusCode != 407) return negotiate.toFailure()

        val challengeToken = negotiate.values("Proxy-Authenticate")
            .firstOrNull { it.startsWith("NTLM ", ignoreCase = true) }
            ?.substring(5)?.trim()
            ?: return ProxyFailure.ProtocolError("El proxy no envió el desafío NTLM")

        val challenge = try {
            NtlmMessages.parseType2(Base64.decode(challengeToken))
        } catch (_: IllegalArgumentException) {
            return ProxyFailure.ProtocolError("El desafío NTLM no es Base64 válido")
        } catch (e: NtlmFormatException) {
            return ProxyFailure.ProtocolError(e.message.orEmpty())
        }

        val authenticate = NtlmMessages.type3(
            challenge = challenge,
            user = NtlmUser.parse(credentials.username),
            password = credentials.password,
            clientChallenge = randomBytes.next(8),
            clientFileTime = clock.now().toFileTime(),
            workstation = WORKSTATION,
        )
        // NTLM autentica la conexión: la respuesta al desafío tiene que viajar por la misma.
        val response = session.connect("NTLM ${Base64.encode(authenticate)}", sameConnection = true)
        return if (response.statusCode in 200..299) null else response.toFailure()
    }

    private companion object {
        const val WORKSTATION = "PROXYUCI"

        // FILETIME: intervalos de 100 ns desde el 1 de enero de 1601.
        const val EPOCH_OFFSET_MILLIS = 11_644_473_600_000L
        const val FILETIME_PER_MILLI = 10_000L

        fun kotlin.time.Instant.toFileTime(): Long = (toEpochMilliseconds() + EPOCH_OFFSET_MILLIS) * FILETIME_PER_MILLI
    }
}
