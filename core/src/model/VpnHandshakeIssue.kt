package org.uci.proxy.core.model

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
