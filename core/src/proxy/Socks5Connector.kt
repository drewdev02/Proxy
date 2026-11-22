package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.ProtocolIssue
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.net.BufferedTcpConnection
import org.uci.proxy.core.port.TcpConnector
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.port.TcpException

/** Túnel mediante SOCKS5 (RFC 1928) con autenticación de usuario y contraseña (RFC 1929). */
class Socks5Connector(private val tcpConnector: TcpConnector) : ProxyConnector {
    override val protocol = ProxyProtocol.SOCKS5

    override suspend fun connect(profile: ProxyProfile, password: String, target: ProxyTarget): ProxyConnectResult {
        var connection: BufferedTcpConnection? = null
        val failure = try {
            connection = BufferedTcpConnection(tcpConnector.connect(profile.host, profile.port))
            handshake(connection, profile.username, password, target)
        } catch (e: TcpException) {
            ProxyFailure.Unreachable(e.kind, e.message.orEmpty())
        }

        return if (failure == null && connection != null) {
            ProxyConnectResult.Connected(connection)
        } else {
            connection?.close()
            ProxyConnectResult.Failed(failure ?: ProxyFailure.Unreachable(NetFailure.OTHER, "sin conexión"))
        }
    }

    private suspend fun handshake(
        connection: BufferedTcpConnection,
        username: String,
        password: String,
        target: ProxyTarget,
    ): ProxyFailure? {
        val hasCredentials = username.isNotEmpty()
        val methods = if (hasCredentials) byteArrayOf(METHOD_NONE, METHOD_USER_PASSWORD) else byteArrayOf(METHOD_NONE)
        connection.write(byteArrayOf(VERSION, methods.size.toByte()) + methods)

        val choice = connection.readExactly(2)
        if (choice[0] != VERSION) return ProxyFailure.ProtocolError(ProtocolIssue.NOT_SOCKS5, "El servidor no habla SOCKS5")
        when (choice[1]) {
            METHOD_NONE -> Unit
            METHOD_USER_PASSWORD -> authenticate(connection, username, password)?.let { return it }
            // 0xFF: ningún método aceptable. Sin credenciales, lo que falta es justo eso.
            else -> return if (hasCredentials) ProxyFailure.UnsupportedAuth(emptyList()) else ProxyFailure.AuthRequired
        }

        val host = target.host.encodeToByteArray()
        if (host.size > MAX_FIELD) return ProxyFailure.Refused(RefusalReason.UNSUPPORTED_REQUEST, "Nombre de destino de más de $MAX_FIELD bytes")
        connection.write(
            byteArrayOf(VERSION, COMMAND_CONNECT, 0, ADDRESS_DOMAIN, host.size.toByte()) +
                host +
                byteArrayOf((target.port ushr 8).toByte(), target.port.toByte()),
        )

        val reply = connection.readExactly(4)
        if (reply[0] != VERSION) return ProxyFailure.ProtocolError(ProtocolIssue.BAD_SOCKS5_REPLY, "Respuesta SOCKS5 no válida")
        if (reply[1] != REPLY_SUCCEEDED) return ProxyFailure.Refused(RefusalReason.ofSocks(reply[1].toInt() and 0xFF), "SOCKS5 código ${reply[1].toInt() and 0xFF}")

        // La respuesta termina con la dirección y el puerto que usó el servidor; se leen para dejar el túnel limpio.
        val addressLength = when (reply[3]) {
            ADDRESS_IPV4 -> 4
            ADDRESS_IPV6 -> 16
            ADDRESS_DOMAIN -> connection.readExactly(1)[0].toInt() and 0xFF
            else -> return ProxyFailure.ProtocolError(ProtocolIssue.BAD_SOCKS5_REPLY, "Tipo de dirección SOCKS5 desconocido")
        }
        connection.readExactly(addressLength + 2)
        return null
    }

    private suspend fun authenticate(connection: BufferedTcpConnection, username: String, password: String): ProxyFailure? {
        val user = username.encodeToByteArray()
        val pass = password.encodeToByteArray()
        if (user.size > MAX_FIELD || pass.size > MAX_FIELD) return ProxyFailure.AuthRejected
        connection.write(byteArrayOf(AUTH_VERSION, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)

        val status = connection.readExactly(2)
        return if (status[1] == AUTH_SUCCESS) null else ProxyFailure.AuthRejected
    }

    private companion object {
        const val VERSION: Byte = 5
        const val AUTH_VERSION: Byte = 1
        const val AUTH_SUCCESS: Byte = 0
        const val METHOD_NONE: Byte = 0
        const val METHOD_USER_PASSWORD: Byte = 2
        const val COMMAND_CONNECT: Byte = 1
        const val ADDRESS_IPV4: Byte = 1
        const val ADDRESS_DOMAIN: Byte = 3
        const val ADDRESS_IPV6: Byte = 4
        const val REPLY_SUCCEEDED: Byte = 0
        const val MAX_FIELD = 255
    }
}
