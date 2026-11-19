package org.uci.proxy.core.platform.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import org.uci.proxy.core.platform.PacketChannel
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Las funciones de `wintun.dll` que hacen falta; los nombres son los de su API (`wintun.h`). */
@Suppress("FunctionName")
internal interface WintunLibrary : Library {
    fun WintunCreateAdapter(name: WString, tunnelType: WString, requestedGuid: Pointer?): Pointer?

    fun WintunOpenAdapter(name: WString): Pointer?

    fun WintunCloseAdapter(adapter: Pointer)

    fun WintunStartSession(adapter: Pointer, capacity: Int): Pointer?

    fun WintunEndSession(session: Pointer)

    fun WintunGetReadWaitEvent(session: Pointer): Pointer

    fun WintunReceivePacket(session: Pointer, packetSize: IntByReference): Pointer?

    fun WintunReleaseReceivePacket(session: Pointer, packet: Pointer)

    fun WintunAllocateSendPacket(session: Pointer, packetSize: Int): Pointer?

    fun WintunSendPacket(session: Pointer, packet: Pointer)
}

/** Abre adaptadores Wintun con la DLL que entrega [locator]. Solo funciona como Administrador. */
class JnaWintunOpener(private val locator: WintunDllLocator) : WintunOpener {
    private val library: WintunLibrary by lazy { Native.load(locator.locate().toString(), WintunLibrary::class.java) }

    override fun open(adapterName: String): PacketChannel {
        val lib = try {
            library
        } catch (e: UnsatisfiedLinkError) {
            throw WindowsNetworkException("No se pudo cargar wintun.dll: ${e.message}")
        }
        val adapter = createAdapter(lib, adapterName)
        val session = lib.WintunStartSession(adapter, RING_CAPACITY)
        if (session == null) {
            val error = failure("iniciar la sesión del adaptador")
            lib.WintunCloseAdapter(adapter)
            throw error
        }
        return WintunChannel(lib, adapter, session)
    }

    /**
     * Si Windows dice que el nombre ya existe (error 52), queda un adaptador anterior: uno que aún está retirando tras
     * desconectar, o uno que dejó la app al cerrarse de golpe. Se abre y se reutiliza, como hace WireGuard; si en ese
     * instante tampoco se puede abrir (está a medio retirar), se reintenta unos segundos.
     */
    private fun createAdapter(lib: WintunLibrary, adapterName: String): Pointer {
        repeat(DUPLICATE_NAME_RETRIES) {
            lib.WintunCreateAdapter(WString(adapterName), WString(TUNNEL_TYPE), guid())?.let { return it }
            val error = Native.getLastError()
            if (error != ERROR_DUP_NAME) throw creationFailure(adapterName, error)
            lib.WintunOpenAdapter(WString(adapterName))?.let { return it }
            Thread.sleep(DUPLICATE_NAME_WAIT_MS)
        }
        return lib.WintunCreateAdapter(WString(adapterName), WString(TUNNEL_TYPE), guid())
            ?: throw creationFailure(adapterName, Native.getLastError())
    }

    private fun creationFailure(adapterName: String, error: Int) = WindowsNetworkException(
        if (error == ERROR_DUP_NAME) {
            "No se pudo crear el adaptador $adapterName: Windows aún no ha retirado el anterior (error de Windows $error)"
        } else {
            "No se pudo crear el adaptador $adapterName (hay que ejecutar como Administrador) (error de Windows $error)"
        },
    )

    private fun failure(action: String) = WindowsNetworkException("No se pudo $action (error de Windows ${Native.getLastError()})")

    // Un GUID fijo hace que Windows reconozca el mismo adaptador, con sus perfiles de red, en cada conexión.
    private fun guid(): Memory = Memory(GUID_BYTES.size.toLong()).apply { write(0, GUID_BYTES, 0, GUID_BYTES.size) }

    private companion object {
        const val TUNNEL_TYPE = "ProxyUCI"

        // ERROR_DUP_NAME: el nombre del adaptador sigue en uso por el anterior, que Windows aún está retirando.
        const val ERROR_DUP_NAME = 52
        const val DUPLICATE_NAME_RETRIES = 20
        const val DUPLICATE_NAME_WAIT_MS = 500L

        /** 4 MiB, como en la versión en C#. */
        const val RING_CAPACITY = 0x400000

        // {6f1d0c52-8a3e-4b7d-9c41-5e27a9d3b816} en el orden en bytes de una estructura GUID de Windows.
        val GUID_BYTES = byteArrayOf(
            0x52, 0x0c, 0x1d, 0x6f, 0x3e, 0x8a.toByte(), 0x7d, 0x4b,
            0x9c.toByte(), 0x41, 0x5e, 0x27, 0xa9.toByte(), 0xd3.toByte(), 0xb8.toByte(), 0x16,
        )
    }
}

private class WintunChannel(
    private val lib: WintunLibrary,
    private val adapter: Pointer,
    private val session: Pointer,
) : PacketChannel {
    private val readEvent = WinNT.HANDLE(lib.WintunGetReadWaitEvent(session))

    // Leer y escribir pueden ir a la vez; cerrar espera a que terminen los que están en curso.
    private val lock = ReentrantReadWriteLock()
    private var closed = false
    private val sizeRef = IntByReference()

    override fun read(timeoutMs: Int): ByteArray? = lock.read { readLocked(timeoutMs) }

    private fun readLocked(timeoutMs: Int): ByteArray? {
        if (closed) return null
        var packet = lib.WintunReceivePacket(session, sizeRef)
        if (packet == null) {
            // Anillo vacío: se espera a que Wintun avise o venza el tope.
            Kernel32.INSTANCE.WaitForSingleObject(readEvent, timeoutMs)
            packet = lib.WintunReceivePacket(session, sizeRef) ?: return null
        }
        val bytes = packet.getByteArray(0, sizeRef.value)
        lib.WintunReleaseReceivePacket(session, packet)
        // Wintun entrega IP sin cabecera de familia; solo interesa IPv4.
        return if (bytes.isEmpty() || (bytes[0].toInt() ushr 4) != IPV4) null else bytes
    }

    override fun write(packet: ByteArray) = lock.read {
        if (!closed) {
            // Si el anillo de salida está lleno el paquete se descarta: TCP lo retransmite.
            lib.WintunAllocateSendPacket(session, packet.size)?.let { target ->
                target.write(0, packet, 0, packet.size)
                lib.WintunSendPacket(session, target)
            }
        }
    }

    override fun close() = lock.write {
        if (!closed) {
            closed = true
            lib.WintunEndSession(session)
            // Al cerrar el último manejador de un adaptador creado por este proceso, Windows lo elimina. Uno reutilizado
            // (abierto con WintunOpenAdapter) se queda, y la próxima conexión lo vuelve a abrir.
            lib.WintunCloseAdapter(adapter)
        }
    }

    private companion object {
        const val IPV4 = 4
    }
}
