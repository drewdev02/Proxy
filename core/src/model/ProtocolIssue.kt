package org.uci.proxy.core.model

/** Qué no cuadra en la respuesta del servidor. */
enum class ProtocolIssue {
    /** No contesta como un proxy HTTP: quizá es SOCKS5, o no es un proxy. */
    NOT_HTTP_PROXY,

    /** No contesta como un proxy SOCKS5: quizá es HTTP, o no es un proxy. */
    NOT_SOCKS5,

    /** Contesta SOCKS5, pero con una respuesta que no se entiende. */
    BAD_SOCKS5_REPLY,

    /** El desafío NTLM del proxy no se puede leer. */
    BAD_NTLM_CHALLENGE,

    /** La app no tiene conector para el protocolo del perfil. */
    NO_CONNECTOR,
}
