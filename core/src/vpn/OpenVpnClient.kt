package org.uci.proxy.core.vpn

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cliente OpenVPN: hace el handshake con un servidor a través de una conexión TCP ya abierta (normalmente por el proxy
 * institucional) y devuelve una [OpenVpnSession] por la que pasan los paquetes IP.
 *
 * Solo implementa lo necesario para los servicios actuales: TCP, `tls-crypt`, usuario y contraseña, y AES-GCM. No
 * renegocia claves: si el servidor lo pide, la sesión termina y hay que reconectar.
 */
class OpenVpnClient(
    private val crypto: OpenVpnCrypto,
    private val tls: TlsSessionFactory,
    private val random: RandomBytes,
    private val logger: Logger,
    private val nowSeconds: () -> Long,
    private val handshakeTimeout: Duration = 30.seconds,
) {
    /**
     * @throws OpenVpnException si el handshake falla; [OpenVpnAuthException] si el servidor rechaza las credenciales.
     */
    suspend fun connect(config: OvpnConfig, connection: TcpConnection, username: String, password: String): OpenVpnSession {
        val framed = FramedConnection(connection)
        try {
            return withTimeout(handshakeTimeout) { handshake(config, framed, username, password) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            framed.close()
            throw OpenVpnException("El servidor VPN no respondió a tiempo", e)
        } catch (e: Exception) {
            framed.close()
            throw e
        }
    }

    private suspend fun handshake(config: OvpnConfig, framed: FramedConnection, username: String, password: String): OpenVpnSession {
        val channel = ControlChannel(framed, TlsCrypt(crypto, config.tlsCryptKey), random, nowSeconds)
        val tlsSession = tls.create(config.caPem)

        channel.send(Opcode.CONTROL_HARD_RESET_CLIENT_V2, ByteArray(0))
        logger.log(LogLevel.DEBUG, TAG, "Handshake: enviado el reinicio del canal de control")

        val clientRandoms = ClientKeySource(random.next(48), random.next(32), random.next(32))
        var expectedPacketId = 0
        var keyMethodSent = false
        var serverHelloSeen = false
        val stream = ByteBuffer()
        var serverKeys: ServerKeySource? = null
        var pushRequested = false
        var pushReply: String? = null
        val pushParts = StringBuilder()

        suspend fun flushTls() {
            while (true) {
                val out = tlsSession.takeOutgoing() ?: return
                out.asList().chunked(MAX_CONTROL_PAYLOAD).forEach { channel.send(Opcode.CONTROL_V1, it.toByteArray()) }
            }
        }

        while (pushReply == null) {
            flushTls()
            if (tlsSession.isEstablished && !keyMethodSent) {
                tlsSession.writeApplicationData(keyMethodMessage(config, clientRandoms, username, password))
                keyMethodSent = true
                logger.log(LogLevel.DEBUG, TAG, "Handshake: TLS establecido, enviando credenciales y claves")
                flushTls()
            }

            val arrival = withTimeoutOrNull(PUSH_RETRY) { Arrival(channel.receive()) }
            if (arrival == null) {
                // Como el cliente oficial: si el servidor aún no estaba listo y no contestó, se vuelve a pedir.
                if (pushRequested) tlsSession.writeApplicationData(PUSH_REQUEST)
                continue
            }
            val received = arrival.received ?: throw OpenVpnException("El servidor VPN cerró la conexión durante el handshake")
            if (received !is Received.Control) continue
            when (received.opcode) {
                Opcode.CONTROL_HARD_RESET_SERVER_V2 -> {
                    if (!serverHelloSeen) {
                        serverHelloSeen = true
                        expectedPacketId = received.packetId + 1
                        logger.log(LogLevel.DEBUG, TAG, "Handshake: el servidor respondió al reinicio")
                    }
                    channel.sendAck(received.packetId)
                }
                Opcode.CONTROL_V1 -> {
                    channel.sendAck(received.packetId)
                    if (received.packetId != expectedPacketId) continue // repetido
                    expectedPacketId++
                    tlsSession.receive(received.payload)
                    while (true) stream.append(tlsSession.readApplicationData() ?: break)
                }
            }

            if (serverKeys == null) serverKeys = parseServerKeySource(stream)
            if (serverKeys != null && !pushRequested) {
                logger.log(LogLevel.DEBUG, TAG, "Handshake: recibidas las claves del servidor, pidiendo configuración")
                tlsSession.writeApplicationData(PUSH_REQUEST)
                pushRequested = true
            }
            if (pushRequested) {
                while (true) {
                    val message = stream.takeNulTerminated() ?: break
                    when {
                        message.startsWith("PUSH_REPLY") -> {
                            pushParts.append(message.removePrefix("PUSH_REPLY"))
                            if (message.contains("push-continuation 2")) {
                                tlsSession.writeApplicationData(PUSH_REQUEST)
                            } else {
                                pushReply = "PUSH_REPLY$pushParts"
                            }
                        }
                        message.startsWith("AUTH_FAILED") ->
                            throw AuthFailedException("El servidor VPN rechazó el usuario o la contraseña")
                        message.startsWith("HALT") || message.startsWith("RESTART") ->
                            throw OpenVpnException("El servidor VPN terminó la sesión: $message")
                    }
                }
            }
        }

        val push = parsePushReply(pushReply)
        val keyBytes = when {
            push.cipher.startsWith("AES-256-GCM") -> 32
            push.cipher.startsWith("AES-128-GCM") -> 16
            else -> throw OpenVpnException("El servidor VPN eligió el cifrado ${push.cipher}, que no se admite (sí AES-GCM)")
        }
        val keys = deriveDataKeys(
            crypto = crypto,
            preMaster = clientRandoms.preMaster,
            clientRandom1 = clientRandoms.random1,
            clientRandom2 = clientRandoms.random2,
            serverRandom1 = serverKeys!!.random1,
            serverRandom2 = serverKeys.random2,
            clientSessionId = channel.localSessionId,
            serverSessionId = channel.remoteSessionId ?: throw OpenVpnException("El servidor no se identificó"),
            cipherKeyBytes = keyBytes,
        )
        logger.log(LogLevel.INFO, TAG, "VPN: sesión establecida (${push.cipher}), dirección ${push.address}")
        return OpenVpnSession(framed, channel, DataChannel(crypto, keys, push.peerId), push, logger)
    }

    /** El mensaje de «key method 2»: claves aleatorias, opciones, credenciales e información del cliente. */
    private fun keyMethodMessage(config: OvpnConfig, source: ClientKeySource, username: String, password: String): ByteArray {
        val cipher = config.cipher ?: "AES-256-GCM"
        val options = "V4,dev-type tun,link-mtu 1541,tun-mtu ${config.tunMtu ?: 1500},proto TCPv4_CLIENT,cipher $cipher," +
            "auth [null-digest],keysize 256,key-method 2,tls-client"
        val peerInfo = "IV_VER=2.6.0\nIV_PLAT=proxyuci\nIV_TCPNL=1\nIV_PROTO=$IV_PROTO\nIV_CIPHERS=AES-256-GCM:AES-128-GCM\n"
        return byteArrayOf(0, 0, 0, 0, 2) + source.preMaster + source.random1 + source.random2 +
            lengthPrefixed(options) + lengthPrefixed(username) + lengthPrefixed(password) + lengthPrefixed(peerInfo)
    }

    private fun lengthPrefixed(text: String): ByteArray {
        if (text.isEmpty()) return u16Bytes(0)
        val bytes = text.encodeToByteArray() + 0
        return u16Bytes(bytes.size) + bytes
    }

    /** Las claves del servidor son 32 + 32 bytes tras la cabecera; después va el texto de opciones, que no hace falta. */
    private fun parseServerKeySource(stream: ByteBuffer): ServerKeySource? {
        val header = 5 + 32 + 32
        if (stream.size < header + 2) return null
        val bytes = stream.peek(header + 2)
        val optionsLength = ((bytes[header].toInt() and 0xFF) shl 8) or (bytes[header + 1].toInt() and 0xFF)
        if (stream.size < header + 2 + optionsLength) return null
        val all = stream.take(header + 2 + optionsLength)
        if (all[4].toInt() != 2) throw OpenVpnException("El servidor VPN usa un método de claves que no se admite")
        return ServerKeySource(all.copyOfRange(5, 37), all.copyOfRange(37, 69))
    }

    private class ClientKeySource(val preMaster: ByteArray, val random1: ByteArray, val random2: ByteArray)

    private class ServerKeySource(val random1: ByteArray, val random2: ByteArray)

    /** Un búfer de bytes del que se va consumiendo por delante. */
    private class ByteBuffer {
        private var data = ByteArray(0)
        val size get() = data.size

        fun append(bytes: ByteArray) {
            data += bytes
        }

        fun peek(count: Int) = data.copyOfRange(0, count)

        fun take(count: Int): ByteArray = data.copyOfRange(0, count).also { data = data.copyOfRange(count, data.size) }

        /** El siguiente mensaje de texto terminado en NUL, o null si aún no ha llegado entero. */
        fun takeNulTerminated(): String? {
            val end = data.indexOf(0)
            if (end < 0) return null
            return take(end + 1).decodeToString(0, end)
        }
    }

    private companion object {
        const val TAG = "Vpn"
        const val MAX_CONTROL_PAYLOAD = 1000
        val PUSH_RETRY = 5.seconds
        val PUSH_REQUEST = "PUSH_REQUEST".encodeToByteArray() + 0

        // Solo «datos v2» (2): el servidor empuja entonces un peer-id y usa ese formato de paquete.
        const val IV_PROTO = 2
    }
}

/** Un paquete de control recibido, o null si el servidor cerró; envuelto para distinguir esto de «no llegó nada». */
private class Arrival(val received: Received?)

/** Una sesión VPN establecida: lee y escribe paquetes IP. */
class OpenVpnSession internal constructor(
    private val framed: FramedConnection,
    private val channel: ControlChannel,
    private val data: DataChannel,
    val push: OpenVpnPush,
    private val logger: Logger,
) : VpnSession {
    private val writeLock = Mutex()

    override val network = VpnNetwork(
        address = push.address,
        prefixLength = VpnNetwork.prefixOf(push.netmask),
        dnsServers = push.dnsServers,
        mtu = push.tunMtu,
    )

    /** Envía un paquete IP al servidor. */
    override suspend fun writePacket(packet: ByteArray) {
        writeLock.withLock { framed.writeFrame(data.seal(packet)) }
    }

    /**
     * Entrega cada paquete IP del servidor a [onPacket] hasta que la sesión termina (el servidor cierra, pide renegociar
     * o se cancela). Mientras tanto envía los mensajes de «sigo aquí» que el servidor exige.
     */
    override suspend fun receive(onPacket: suspend (ByteArray) -> Unit) = coroutineScope {
        val keepAlive = launch {
            while (true) {
                delay(push.pingSeconds.seconds)
                writeLock.withLock { framed.writeFrame(data.seal(PING)) }
            }
        }
        try {
            while (true) {
                // El servidor envía algo al menos cada `ping` segundos; si pasa `ping-restart` sin noticias, está caído.
                val outcome = withTimeoutOrNull(push.pingRestartSeconds.seconds) { Arrival(channel.receive()) }
                if (outcome == null) {
                    logger.log(LogLevel.WARN, "Vpn", "Sin noticias del servidor VPN en ${push.pingRestartSeconds} s: la sesión termina")
                    break
                }
                when (val received = outcome.received ?: break) {
                    is Received.Data -> {
                        val plain = data.open(received.wire)
                        if (plain == null) {
                            logger.log(LogLevel.WARN, "Vpn", "Paquete de datos que no se autentica (${received.wire.size} bytes)")
                            continue
                        }
                        if (plain.contentEquals(PING)) {
                            logger.log(LogLevel.DEBUG, "Vpn", "Señal de vida del servidor")
                        } else {
                            onPacket(plain)
                        }
                    }
                    is Received.Control -> {
                        if (received.opcode == Opcode.CONTROL_SOFT_RESET_V1) {
                            logger.log(LogLevel.WARN, "Vpn", "El servidor pidió renegociar las claves, que no se admite: la sesión termina")
                            break
                        }
                        if (received.packetId >= 0) channel.sendAck(received.packetId)
                    }
                }
            }
        } finally {
            keepAlive.cancel()
        }
    }

    override fun close() = framed.close()

    private companion object {
        // Mensaje fijo que OpenVPN envía por el canal de datos como señal de vida.
        val PING = byteArrayOf(
            0x2a, 0x18, 0x7b, 0xf3.toByte(), 0x64, 0x1e, 0xb4.toByte(), 0xcb.toByte(),
            0x07, 0xed.toByte(), 0x2d, 0x0a, 0x98.toByte(), 0x1f, 0xc7.toByte(), 0x48,
        )
    }
}
