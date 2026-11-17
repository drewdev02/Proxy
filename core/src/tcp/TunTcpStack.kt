package org.uci.proxy.core.tcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.uci.proxy.core.packet.Ipv4Packet
import org.uci.proxy.core.packet.TcpFlags
import org.uci.proxy.core.packet.TcpSegment
import org.uci.proxy.core.packet.buildTcpPacket
import org.uci.proxy.core.port.PacketWriter
import org.uci.proxy.core.port.RandomBytes
import org.uci.proxy.core.port.TcpException

/*
 * Terminador TCP en espacio de usuario sobre la interfaz TUN. Port de TunTcpStack.cs de SuperProxyWin.
 *
 * Las apps creen hablar con el servidor remoto; en realidad cada conexión termina aquí, y sus
 * bytes se entregan a quien acepta la conexión (el relé hacia el proxy).
 *
 * Todo el estado se protege con un único candado por pila. Los paquetes se envían fuera del
 * candado: TCP tolera que dos envíos concurrentes lleguen en otro orden.
 */

private const val MSS = 1360
private const val MIN_PEER_MSS = 536
private const val MAX_RECEIVE_BUFFER = 262_144
private const val MAX_OUT_OF_ORDER_SEGMENTS = 32
private const val MAX_WINDOW = 65_535

private const val TICK_MS = 200L
private const val SYN_ACK_RETRY_MS = 500L
private const val MAX_SYN_ACK_RETRIES = 6
private const val BASE_RETRANSMIT_MS = 350L
private const val MAX_RETRANSMIT_MS = 3_000L
private const val MAX_RETRANSMITS = 10
private const val CLOSED_LINGER_MS = 4_000L
private const val FIN_LINGER_MS = 6_000L
private const val IDLE_TIMEOUT_MS = 300_000L
private const val WINDOW_WAIT_MS = 350L

private fun seqLt(a: Int, b: Int) = a - b < 0
private fun seqLeq(a: Int, b: Int) = a - b <= 0
private fun seqGt(a: Int, b: Int) = a - b > 0

internal data class FlowKey(val clientIp: Int, val clientPort: Int, val remoteIp: Int, val remotePort: Int)

private enum class State { SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT, CLOSED }

private class SentSegment(val sequence: Int, val nextSequence: Int, val flags: Int, val payload: ByteArray) {
    var lastSentMs = 0L
    var retries = 0
}

/**
 * @param output interfaz por la que se devuelven los paquetes a las apps.
 * @param scope ámbito de la sesión: en él corren el temporizador y cada conexión aceptada.
 * @param nowMs reloj monótono en milisegundos.
 * @param onAccept se invoca, en su propia corrutina, por cada conexión que completa el saludo.
 */
class TunTcpStack(
    private val output: PacketWriter,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val randomBytes: RandomBytes,
    private val onAccept: suspend (TunTcpConnection) -> Unit,
) {
    private val lock = Mutex()
    private val connections = HashMap<FlowKey, TunTcpConnection>()

    /** Arranca el temporizador de retransmisiones. Vive mientras viva [scope]. */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /**
     * Procesa un segmento que llega de una app.
     *
     * @param destinationHost nombre del destino si se conoce (viene del DNS falso), para pasárselo a quien acepte.
     */
    suspend fun process(packet: Ipv4Packet, segment: TcpSegment, destinationHost: String?) {
        val key = FlowKey(packet.source, segment.sourcePort, packet.destination, segment.destinationPort)
        val out = ArrayList<ByteArray>(2)
        var accepted: TunTcpConnection? = null

        lock.withLock {
            val existing = connections[key]
            when {
                segment.has(TcpFlags.RST) -> {
                    if (existing != null) {
                        connections.remove(key)
                        existing.abortLocked()
                    }
                }
                segment.has(TcpFlags.SYN) && !segment.has(TcpFlags.ACK) -> {
                    if (existing != null && existing.isSameSyn(segment.sequence)) {
                        // El SYN se repitió porque nuestro SYN-ACK se perdió o tarda.
                        out += existing.synAckLocked(nowMs())
                    } else {
                        if (existing != null) {
                            connections.remove(key)
                            existing.abortLocked()
                        }
                        val connection = TunTcpConnection(
                            stack = this,
                            key = key,
                            destinationHost = destinationHost,
                            iss = randomSequence(),
                            irs = segment.sequence,
                            sendMss = (segment.maxSegmentSize ?: MSS).coerceIn(MIN_PEER_MSS, MSS),
                            peerWindow = maxOf(segment.window, 4096),
                            now = nowMs(),
                        )
                        connections[key] = connection
                        out += connection.synAckLocked(nowMs())
                    }
                }
                existing == null -> {
                    // Segmento de una conexión que no conocemos: un RST hace que la app la descarte ya.
                    out += if (segment.has(TcpFlags.ACK)) {
                        resetPacket(key, sequence = segment.acknowledgment, acknowledgment = 0, flags = TcpFlags.RST)
                    } else {
                        resetPacket(
                            key,
                            sequence = 0,
                            acknowledgment = segment.sequence + segment.payloadLength,
                            flags = TcpFlags.RST or TcpFlags.ACK,
                        )
                    }
                }
                else -> {
                    if (existing.onSegmentLocked(segment, nowMs(), out)) accepted = existing
                    if (existing.isFinished) connections.remove(key)
                }
            }
        }

        out.forEach { output.writePacket(it) }
        accepted?.let { connection -> scope.launch { onAccept(connection) } }
    }

    private suspend fun tick() {
        val out = ArrayList<ByteArray>()
        lock.withLock {
            val now = nowMs()
            val iterator = connections.values.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().onTickLocked(now, out)) iterator.remove()
            }
        }
        out.forEach { output.writePacket(it) }
    }

    /** Cierra todas las conexiones sin avisar a las apps; se usa al detener el túnel. */
    suspend fun shutdown() {
        lock.withLock {
            connections.values.forEach { it.abortLocked() }
            connections.clear()
        }
    }

    internal suspend fun <T> locked(block: () -> T): T = lock.withLock { block() }

    internal suspend fun send(packets: List<ByteArray>) = packets.forEach { output.writePacket(it) }

    internal fun removeLocked(key: FlowKey) {
        connections.remove(key)
    }

    internal fun now(): Long = nowMs()

    private fun randomSequence(): Int =
        randomBytes.next(4).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }

    private fun resetPacket(key: FlowKey, sequence: Int, acknowledgment: Int, flags: Int): ByteArray = buildTcpPacket(
        source = key.remoteIp,
        destination = key.clientIp,
        sourcePort = key.remotePort,
        destinationPort = key.clientPort,
        sequence = sequence,
        acknowledgment = acknowledgment,
        flags = flags,
        window = 0,
    )
}

/**
 * Una conexión TCP de una app, vista desde el túnel: lo que la app envía se lee con [read] y lo
 * que se escribe con [write] le llega como si viniera del servidor remoto.
 */
class TunTcpConnection internal constructor(
    private val stack: TunTcpStack,
    private val key: FlowKey,
    /** Nombre del destino si la app lo resolvió con el DNS falso; null si conectó a una IP. */
    val destinationHost: String?,
    private val iss: Int,
    private val irs: Int,
    private val sendMss: Int,
    private var peerWindow: Int,
    now: Long,
) {
    /** Dirección y puerto a los que la app cree estar conectando. */
    val destinationAddress: Int get() = key.remoteIp
    val destinationPort: Int get() = key.remotePort

    private var state = State.SYN_RECEIVED
    private var receiveNext = irs + 1
    private var sendUnacknowledged = iss
    private var sendNext = iss + 1

    private var acceptDispatched = false
    private var remoteFinished = false
    private var finSeen = false
    private var finSequence = 0
    private var localFinQueued = false
    private var aborted = false
    private var closed = false

    private var lastActivityMs = now
    private var lastSynAckMs = now
    private var synAckRetries = 0
    private var closedAtMs = 0L

    private val received = ArrayDeque<ByteArray>()
    private var receivedHeadOffset = 0
    private var unreadBytes = 0
    private val outOfOrder = HashMap<Int, ByteArray>()
    private var receiveWaiter: CompletableDeferred<Unit>? = null

    private val unacknowledged = ArrayDeque<SentSegment>()
    private var windowWaiter: CompletableDeferred<Unit>? = null

    internal val isFinished: Boolean get() = closed

    internal fun isSameSyn(sequence: Int): Boolean = state == State.SYN_RECEIVED && irs == sequence

    internal fun synAckLocked(now: Long): ByteArray {
        lastSynAckMs = now
        return packet(iss, TcpFlags.SYN or TcpFlags.ACK, mssOption = MSS)
    }

    /**
     * Aplica un segmento entrante. Los paquetes de respuesta se añaden a [out].
     *
     * @return true si con este segmento la conexión quedó establecida y hay que entregarla.
     */
    internal fun onSegmentLocked(segment: TcpSegment, now: Long, out: MutableList<ByteArray>): Boolean {
        if (aborted || closed) return false
        lastActivityMs = now
        var accept = false
        var sendAck = false

        if (segment.has(TcpFlags.ACK)) {
            peerWindow = segment.window
            val ack = segment.acknowledgment

            if (state == State.SYN_RECEIVED) {
                if (ack != sendNext) return false
                sendUnacknowledged = ack
                state = State.ESTABLISHED
                if (!acceptDispatched) {
                    acceptDispatched = true
                    accept = true
                }
            } else if (seqGt(ack, sendUnacknowledged) && seqLeq(ack, sendNext)) {
                sendUnacknowledged = ack
                while (unacknowledged.isNotEmpty() && seqLeq(unacknowledged.first().nextSequence, ack)) {
                    unacknowledged.removeFirst()
                }
                wakeWriter()
            } else if (peerWindow > 0) {
                // ACK que solo reabre la ventana.
                wakeWriter()
            }

            if (localFinQueued && sendUnacknowledged == sendNext && remoteFinished) finishLocked(now)
        }
        if (state == State.SYN_RECEIVED) return false

        val payloadLength = segment.payloadLength
        if (payloadLength > 0 && !remoteFinished) {
            val sequence = segment.sequence
            when {
                // Retransmisión que se solapa en parte con lo ya recibido: se aprovecha solo lo nuevo.
                seqLt(sequence, receiveNext) -> {
                    val overlap = receiveNext - sequence
                    if (overlap < payloadLength) acceptInOrder(segment.payload.copyOfRange(overlap, payloadLength))
                }
                sequence == receiveNext -> acceptInOrder(segment.payload)
                outOfOrder.size < MAX_OUT_OF_ORDER_SEGMENTS && sequence !in outOfOrder ->
                    outOfOrder[sequence] = segment.payload
            }
            sendAck = true
        }

        if (segment.has(TcpFlags.FIN) && !finSeen) {
            finSeen = true
            finSequence = segment.sequence + payloadLength
        }
        // El FIN solo cuenta cuando ya se recibió todo lo anterior a él.
        if (finSeen && !remoteFinished && receiveNext == finSequence) {
            receiveNext += 1
            remoteFinished = true
            sendAck = true
            if (state == State.ESTABLISHED) state = State.CLOSE_WAIT
            wakeReader()
            if (localFinQueued && sendUnacknowledged == sendNext) finishLocked(now)
        }

        if (sendAck) out += packet(sendNext, TcpFlags.ACK)
        return accept
    }

    private fun acceptInOrder(data: ByteArray) {
        if (data.isEmpty() || unreadBytes >= MAX_RECEIVE_BUFFER * 2) return
        enqueue(data)
        // Lo que había llegado adelantado y ahora encaja se entrega a continuación.
        while (true) {
            val next = outOfOrder.remove(receiveNext) ?: break
            enqueue(next)
        }
        wakeReader()
    }

    private fun enqueue(data: ByteArray) {
        received.addLast(data)
        unreadBytes += data.size
        receiveNext += data.size
    }

    /**
     * Lee lo que la app envió. Suspende hasta que haya datos.
     *
     * @return null cuando la app cerró su lado o la conexión se abortó.
     */
    suspend fun read(maxBytes: Int = DEFAULT_READ_SIZE): ByteArray? {
        while (true) {
            var result: ByteArray? = null
            var windowUpdate: ByteArray? = null
            var waiter: CompletableDeferred<Unit>? = null

            val ended = stack.locked {
                when {
                    aborted -> true
                    received.isNotEmpty() -> {
                        val windowBefore = advertisedWindow()
                        result = takeReceived(maxBytes)
                        // Si la ventana estaba casi cerrada, se avisa a la app de que ya puede seguir enviando.
                        if (windowBefore < 16_384 && advertisedWindow() >= 32_768) {
                            windowUpdate = packet(sendNext, TcpFlags.ACK)
                        }
                        false
                    }
                    remoteFinished -> true
                    else -> {
                        waiter = receiveWaiter ?: CompletableDeferred<Unit>().also { receiveWaiter = it }
                        false
                    }
                }
            }

            windowUpdate?.let { stack.send(listOf(it)) }
            result?.let { return it }
            if (ended) return null
            waiter?.await()
        }
    }

    private fun takeReceived(maxBytes: Int): ByteArray {
        val out = ByteArray(minOf(maxBytes, unreadBytes))
        var copied = 0
        while (copied < out.size) {
            val head = received.first()
            val take = minOf(head.size - receivedHeadOffset, out.size - copied)
            head.copyInto(out, copied, receivedHeadOffset, receivedHeadOffset + take)
            copied += take
            receivedHeadOffset += take
            if (receivedHeadOffset == head.size) {
                received.removeFirst()
                receivedHeadOffset = 0
            }
        }
        unreadBytes -= copied
        return out
    }

    /**
     * Envía [bytes] a la app, partiéndolos en segmentos y respetando su ventana de recepción.
     *
     * @throws TcpException si la conexión ya está cerrada o abortada.
     */
    suspend fun write(bytes: ByteArray) {
        var position = 0
        while (position < bytes.size) {
            var segmentPacket: ByteArray? = null
            var waiter: CompletableDeferred<Unit>? = null

            stack.locked {
                if (aborted || localFinQueued) throw TcpException("La conexión de la app ya está cerrada")

                val inFlight = sendNext - sendUnacknowledged
                var available = peerWindow.coerceIn(0, MAX_WINDOW) - inFlight
                // Ventana a cero y nada en vuelo: se manda un byte de sondeo para descubrir cuándo reabre.
                if (available <= 0 && peerWindow == 0 && inFlight == 0) available = 1

                if (available > 0) {
                    val length = minOf(bytes.size - position, sendMss, available)
                    val payload = bytes.copyOfRange(position, position + length)
                    val segment = SentSegment(sendNext, sendNext + length, TcpFlags.ACK or TcpFlags.PSH, payload)
                    val now = stack.now()
                    segment.lastSentMs = now
                    lastActivityMs = now
                    sendNext += length
                    unacknowledged.addLast(segment)
                    segmentPacket = packet(segment.sequence, segment.flags, payload)
                    position += length
                } else {
                    waiter = windowWaiter ?: CompletableDeferred<Unit>().also { windowWaiter = it }
                }
            }

            segmentPacket?.let { stack.send(listOf(it)) }
            // El tope de espera cubre el caso de que el aviso de ventana se pierda.
            waiter?.let { pending -> withTimeoutOrNull(WINDOW_WAIT_MS) { pending.await() } }
        }
    }

    /** Cierra nuestro lado: la app ve que el servidor terminó de enviar. Lo pendiente de confirmar se sigue retransmitiendo. */
    suspend fun finish() {
        val fin = stack.locked {
            when {
                aborted || closed || localFinQueued -> null
                state == State.SYN_RECEIVED -> {
                    localFinQueued = true
                    aborted = true
                    closed = true
                    stack.removeLocked(key)
                    null
                }
                else -> {
                    localFinQueued = true
                    val segment = SentSegment(sendNext, sendNext + 1, TcpFlags.FIN or TcpFlags.ACK, ByteArray(0))
                    sendNext += 1
                    state = if (remoteFinished) State.LAST_ACK else State.FIN_WAIT
                    closedAtMs = stack.now()
                    segment.lastSentMs = closedAtMs
                    unacknowledged.addLast(segment)
                    packet(segment.sequence, segment.flags)
                }
            }
        }
        fin?.let { stack.send(listOf(it)) }
    }

    /** Corta la conexión con un RST: la app recibe un error en vez de un cierre ordenado. */
    suspend fun abort() {
        val reset = stack.locked {
            if (aborted) {
                null
            } else {
                val packet = packet(sendNext, TcpFlags.RST or TcpFlags.ACK, window = 0)
                abortLocked()
                stack.removeLocked(key)
                packet
            }
        }
        reset?.let { stack.send(listOf(it)) }
    }

    internal fun abortLocked() {
        if (aborted) return
        aborted = true
        closed = true
        state = State.CLOSED
        unacknowledged.clear()
        wakeReader()
        wakeWriter()
    }

    /**
     * Retransmisiones y caducidades.
     *
     * @return true si la conexión debe retirarse de la pila.
     */
    internal fun onTickLocked(now: Long, out: MutableList<ByteArray>): Boolean {
        when {
            aborted || closed -> return closedAtMs > 0 && now - closedAtMs > CLOSED_LINGER_MS || aborted
            state == State.SYN_RECEIVED -> {
                if (now - lastSynAckMs >= SYN_ACK_RETRY_MS) {
                    if (synAckRetries >= MAX_SYN_ACK_RETRIES) return resetOnTick(out)
                    synAckRetries++
                    out += synAckLocked(now)
                }
            }
            unacknowledged.isNotEmpty() -> {
                val head = unacknowledged.first()
                val timeout = minOf(BASE_RETRANSMIT_MS shl minOf(head.retries, 4), MAX_RETRANSMIT_MS)
                if (now - head.lastSentMs >= timeout) {
                    if (head.retries >= MAX_RETRANSMITS) return resetOnTick(out)
                    head.retries++
                    head.lastSentMs = now
                    out += packet(head.sequence, head.flags, head.payload)
                }
            }
            localFinQueued && now - closedAtMs > FIN_LINGER_MS -> return true
            now - lastActivityMs > IDLE_TIMEOUT_MS -> return resetOnTick(out)
        }
        return false
    }

    private fun resetOnTick(out: MutableList<ByteArray>): Boolean {
        out += packet(sendNext, TcpFlags.RST or TcpFlags.ACK, window = 0)
        abortLocked()
        return true
    }

    private fun finishLocked(now: Long) {
        state = State.CLOSED
        closed = true
        closedAtMs = now
    }

    private fun wakeReader() {
        receiveWaiter?.complete(Unit)
        receiveWaiter = null
    }

    private fun wakeWriter() {
        windowWaiter?.complete(Unit)
        windowWaiter = null
    }

    private fun advertisedWindow(): Int = (MAX_RECEIVE_BUFFER - unreadBytes).coerceIn(0, MAX_WINDOW)

    /** Paquete de nuestro lado hacia la app, con el ACK y la ventana actuales. */
    private fun packet(
        sequence: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        mssOption: Int? = null,
        window: Int = advertisedWindow(),
    ): ByteArray = buildTcpPacket(
        source = key.remoteIp,
        destination = key.clientIp,
        sourcePort = key.remotePort,
        destinationPort = key.clientPort,
        sequence = sequence,
        acknowledgment = receiveNext,
        flags = flags,
        window = window,
        payload = payload,
        mssOption = mssOption,
    )

    private companion object {
        const val DEFAULT_READ_SIZE = 16_384
    }
}
