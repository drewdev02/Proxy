package org.uci.proxy.core.vpn

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

/** Por qué falló el handshake con un servidor OpenVPN. */
enum class VpnHandshakeIssue {
    /** El servidor no respondió a tiempo. */
    TIMEOUT,

    /** El servidor cerró la conexión a mitad del handshake. */
    CLOSED,

    /** El certificado del servidor no lo firma la autoridad (`<ca>`) del archivo: no es el servidor que se espera. */
    SERVER_CERTIFICATE,

    /** El TLS falló por otro motivo. */
    TLS_FAILED,

    /** El servidor eligió un cifrado de datos que no se admite (solo AES-GCM); [OpenVpnException.subject] dice cuál. */
    UNSUPPORTED_CIPHER,

    /** El servidor usa algo del protocolo que el cliente no implementa (método de claves, formato de datos…). */
    UNSUPPORTED_SERVER,

    /** El servidor terminó la sesión (HALT o RESTART); [OpenVpnException.subject] lleva su motivo. */
    SESSION_ENDED,

    /** Esta plataforma no tiene cliente OpenVPN. */
    UNAVAILABLE,
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
