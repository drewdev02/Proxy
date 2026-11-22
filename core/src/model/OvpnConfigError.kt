package org.uci.proxy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Por qué un `.ovpn` no sirve para este cliente. Sin textos: la interfaz decide cómo contarlo en cada idioma.
 */
@Serializable
sealed interface OvpnConfigError {
    /** Un bloque como `<ca>` se abre y no se cierra. */
    @Serializable @SerialName("unclosed_block")
    data class UnclosedBlock(val block: String) : OvpnConfigError

    /** Falta `client` o `tls-client`: es un perfil de servidor u otra cosa. */
    @Serializable @SerialName("not_a_client")
    data object NotAClient : OvpnConfigError

    /** `dev tap`: solo se admite `tun`. */
    @Serializable @SerialName("tap_not_supported")
    data object TapNotSupported : OvpnConfigError

    /** Ningún `remote` es TCP; por un proxy solo pasa OpenVPN sobre TCP. */
    @Serializable @SerialName("no_tcp_servers")
    data object NoTcpServers : OvpnConfigError

    @Serializable @SerialName("tls_auth_not_supported")
    data object TlsAuthNotSupported : OvpnConfigError

    @Serializable @SerialName("tls_crypt_v2_not_supported")
    data object TlsCryptV2NotSupported : OvpnConfigError

    /** El perfil pide certificado de cliente; solo se admite usuario y contraseña. */
    @Serializable @SerialName("client_certificate_not_supported")
    data object ClientCertificateNotSupported : OvpnConfigError

    /** `compress` o `comp-lzo` activos; [algorithm] es el que pide el archivo (`lz4-v2`, `LZO`…). */
    @Serializable @SerialName("compression_not_supported")
    data class CompressionNotSupported(val algorithm: String) : OvpnConfigError

    /** No trae el certificado de la autoridad (`<ca>`). */
    @Serializable @SerialName("missing_ca")
    data object MissingCa : OvpnConfigError

    /** No trae `<tls-crypt>`, que es lo único que se admite. */
    @Serializable @SerialName("missing_tls_crypt")
    data object MissingTlsCrypt : OvpnConfigError

    /** La clave de `<tls-crypt>` no son 256 bytes en hexadecimal. */
    @Serializable @SerialName("bad_tls_crypt_key")
    data object BadTlsCryptKey : OvpnConfigError

    /** El certificado de la autoridad (`<ca>`) no se puede leer. */
    @Serializable @SerialName("invalid_ca")
    data object InvalidCa : OvpnConfigError
}
