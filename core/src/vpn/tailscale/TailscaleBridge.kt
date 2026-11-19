package org.uci.proxy.core.vpn.tailscale

/**
 * La librería nativa de Tailscale (libtsbridge, en tools/tsbridge). Todo lo que no son paquetes viaja como JSON. Las
 * llamadas son bloqueantes: quien las usa las hace fuera del hilo principal.
 *
 * Solo admite una sesión abierta a la vez por proceso.
 */
interface TailscaleBridge {
    /** Si la librería está en este equipo y se pudo cargar. */
    val isAvailable: Boolean

    /** Abre una sesión: `{"handle":n,"network":{...}}` o `{"error":{"code":"...","message":"..."}}`. Tarda lo que tarde el control. */
    fun start(configJson: String): String

    /** Corta el [start] en curso, que devuelve enseguida con un fallo. */
    fun cancelStart()

    /** Entrega un paquete IP a Tailscale. 0 si se aceptó, -1 si la sesión ya no existe. */
    fun write(handle: Long, packet: ByteArray, length: Int): Int

    /** Espera un paquete IP: su longitud, 0 si pasó el tiempo, -1 si la sesión terminó. */
    fun read(handle: Long, buffer: ByteArray, timeoutMs: Int): Int

    /** `{"exitNodes":[...]}` o `{"error":{...}}`. */
    fun exitNodes(handle: Long): String

    fun close(handle: Long)

    /** La siguiente línea de registro de la librería, o null si no llega ninguna a tiempo. */
    fun nextLog(timeoutMs: Int): String?

    /** Donde no hay librería (iOS, o un escritorio sin ella). */
    object Unavailable : TailscaleBridge {
        override val isAvailable = false
        override fun start(configJson: String) = """{"error":{"code":"INTERNAL","message":"Tailscale no está disponible"}}"""
        override fun cancelStart() = Unit
        override fun write(handle: Long, packet: ByteArray, length: Int) = -1
        override fun read(handle: Long, buffer: ByteArray, timeoutMs: Int) = -1
        override fun exitNodes(handle: Long) = """{"error":{"code":"INTERNAL","message":"Tailscale no está disponible"}}"""
        override fun close(handle: Long) = Unit
        override fun nextLog(timeoutMs: Int): String? = null
    }
}
