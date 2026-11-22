package org.uci.proxy.core.vpn

import org.uci.proxy.core.model.VpnHandshakeIssue

/**
 * Un TLS que trabaja sobre buffers en vez de sobre un socket: OpenVPN lleva el handshake TLS dentro de sus propios
 * paquetes de control, así que lo que sale de aquí hay que meterlo en esos paquetes.
 */
interface TlsSession {
    /** True cuando el handshake terminó y se puede enviar y recibir datos de aplicación. */
    val isEstablished: Boolean

    /** Entrega los bytes TLS que llegaron del servidor. @throws OpenVpnException si el handshake falla. */
    fun receive(ciphertext: ByteArray)

    /** Datos de aplicación ya descifrados que hayan llegado, o null si no hay. */
    fun readApplicationData(): ByteArray?

    /** Cifra datos de aplicación para el servidor. */
    fun writeApplicationData(plaintext: ByteArray)

    /** Los bytes TLS pendientes de enviar al servidor, o null si no hay. */
    fun takeOutgoing(): ByteArray?
}

/** Crea sesiones TLS de cliente que confían solo en la autoridad del perfil. */
fun interface TlsSessionFactory {
    /** @param caPem la autoridad que firma al servidor. */
    fun create(caPem: String): TlsSession
}

/**
 * Un fallo del handshake. El mensaje es para el registro; [issue] es lo que la interfaz cuenta, y [subject] el dato
 * que lo concreta (el cifrado, el motivo del servidor), si lo hay.
 */
open class OpenVpnException(
    val issue: VpnHandshakeIssue,
    message: String,
    cause: Throwable? = null,
    val subject: String? = null,
) : Exception(message, cause)

/** El certificado de la autoridad del perfil no se puede leer: el archivo está dañado, no el servidor. */
class InvalidCertificateException(message: String, cause: Throwable? = null) :
    OpenVpnException(VpnHandshakeIssue.TLS_FAILED, message, cause)
