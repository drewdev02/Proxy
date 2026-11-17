package org.uci.proxy.core.platform.macos

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.uci.proxy.core.platform.PacketChannel

/** Las funciones de libc que hacen falta para abrir y usar una interfaz utun. */
@Suppress("FunctionName")
internal interface LibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int

    // ioctl es variádica en C. En Apple Silicon esos argumentos se pasan de otra forma, así que
    // hay que declararla también variádica aquí para que JNA use la convención correcta.
    fun ioctl(fd: Int, request: NativeLong, vararg args: Any): Int

    fun connect(fd: Int, address: Pointer, length: Int): Int

    fun getsockopt(fd: Int, level: Int, name: Int, value: Pointer, length: IntByReference): Int

    fun poll(fds: Pointer, count: Int, timeoutMs: Int): Int

    fun read(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong

    fun write(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong

    fun close(fd: Int): Int
}

/** Una interfaz utun abierta: su nombre (por ejemplo "utun7") y el descriptor por el que pasan los paquetes. */
interface UtunHandle : PacketChannel {
    val name: String
}

fun interface UtunOpener {
    /** @throws MacNetworkException si el sistema no deja crear la interfaz, normalmente por no ser root. */
    fun open(): UtunHandle
}

/**
 * Abre una interfaz utun del núcleo de macOS mediante su socket de control.
 * Solo funciona como root. La interfaz y sus rutas desaparecen al cerrar el descriptor o morir el proceso.
 */
class JnaUtunOpener : UtunOpener {
    private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

    override fun open(): UtunHandle {
        val fd = libc.socket(PF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL)
        if (fd < 0) throw failure("crear el socket de control")
        try {
            // El núcleo identifica el control "utun" con un número que hay que preguntarle.
            val info = Memory(CTL_INFO_SIZE).apply {
                clear()
                setString(4, UTUN_CONTROL_NAME, "US-ASCII")
            }
            if (libc.ioctl(fd, NativeLong(CTLIOCGINFO), info) < 0) throw failure("localizar el control utun")
            val controlId = info.getInt(0)

            val address = Memory(SOCKADDR_CTL_SIZE).apply {
                clear()
                setByte(0, SOCKADDR_CTL_SIZE.toByte())
                setByte(1, AF_SYSTEM.toByte())
                setShort(2, AF_SYS_CONTROL.toShort())
                setInt(4, controlId)
                setInt(8, 0) // unidad 0: que el sistema elija el primer utunN libre
            }
            if (libc.connect(fd, address, SOCKADDR_CTL_SIZE.toInt()) < 0) throw failure("crear la interfaz utun")

            val nameBuffer = Memory(IFNAME_SIZE).apply { clear() }
            val nameLength = IntByReference(IFNAME_SIZE.toInt())
            if (libc.getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, nameBuffer, nameLength) < 0) {
                throw failure("leer el nombre de la interfaz")
            }
            return JnaUtunHandle(libc, fd, nameBuffer.getString(0, "US-ASCII"))
        } catch (e: MacNetworkException) {
            libc.close(fd)
            throw e
        }
    }

    private fun failure(action: String) = MacNetworkException("No se pudo $action (errno ${Native.getLastError()})")

    private companion object {
        const val PF_SYSTEM = 32
        const val AF_SYSTEM = 32
        const val AF_SYS_CONTROL = 2
        const val SOCK_DGRAM = 2
        const val SYSPROTO_CONTROL = 2
        const val UTUN_OPT_IFNAME = 2
        const val UTUN_CONTROL_NAME = "com.apple.net.utun_control"

        // struct ctl_info { u_int32_t ctl_id; char ctl_name[96]; }
        const val CTL_INFO_SIZE = 100L

        // _IOWR('N', 3, struct ctl_info)
        const val CTLIOCGINFO = 0xC0644E03L
        const val SOCKADDR_CTL_SIZE = 32L
        const val IFNAME_SIZE = 16L
    }
}

private class JnaUtunHandle(private val libc: LibC, private val fd: Int, override val name: String) : UtunHandle {
    @Volatile
    private var closed = false
    private val buffer = ByteArray(MAX_PACKET + HEADER_SIZE)
    private val pollFd = Memory(POLLFD_SIZE).apply {
        setInt(0, fd)
        setShort(4, POLLIN)
        setShort(6, 0)
    }

    override fun read(timeoutMs: Int): ByteArray? {
        // Se sondea con tope en vez de bloquear en read(): así close() desde otro hilo se atiende pronto.
        if (closed || libc.poll(pollFd, 1, timeoutMs) <= 0 || closed) return null
        val count = libc.read(fd, buffer, NativeLong(buffer.size.toLong())).toInt()
        if (count <= HEADER_SIZE) return null
        // Cada paquete llega precedido de 4 bytes con la familia de protocolo; solo interesa IPv4.
        if (buffer[3].toInt() != AF_INET) return null
        return buffer.copyOfRange(HEADER_SIZE, count)
    }

    override fun write(packet: ByteArray) {
        if (closed) return
        val framed = ByteArray(HEADER_SIZE + packet.size)
        framed[3] = AF_INET.toByte()
        packet.copyInto(framed, HEADER_SIZE)
        libc.write(fd, framed, NativeLong(framed.size.toLong()))
    }

    override fun close() {
        if (closed) return
        closed = true
        libc.close(fd)
    }

    private companion object {
        const val HEADER_SIZE = 4
        const val MAX_PACKET = 65_535
        const val AF_INET = 2
        const val POLLIN: Short = 1

        // struct pollfd { int fd; short events; short revents; }
        const val POLLFD_SIZE = 8L
    }
}
