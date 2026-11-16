package org.uci.proxy.core.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol

/** Lo que la persona escribe en el formulario, todavía sin validar. */
data class ProfileDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.HTTP,
    val username: String = "",
)

enum class ProfileField { NAME, HOST, PORT }

sealed interface ProfileValidation {
    data class Valid(val profile: ProxyProfile) : ProfileValidation
    data class Invalid(val errors: Set<ProfileField>) : ProfileValidation
}

class ProfileValidator {
    /** Devuelve el perfil ya normalizado, o los campos que hay que corregir. */
    fun validate(id: String, draft: ProfileDraft): ProfileValidation {
        val name = draft.name.trim()
        val host = draft.host.trim()
        val port = draft.port.trim().toIntOrNull()?.takeIf { it in PORT_RANGE }

        val errors = buildSet {
            if (name.isEmpty()) add(ProfileField.NAME)
            if (!isValidHost(host)) add(ProfileField.HOST)
            if (port == null) add(ProfileField.PORT)
        }
        if (errors.isNotEmpty() || port == null) return ProfileValidation.Invalid(errors)

        return ProfileValidation.Valid(
            ProxyProfile(
                id = id,
                name = name,
                host = host,
                port = port,
                protocol = draft.protocol,
                username = draft.username.trim(),
            ),
        )
    }

    // Se espera un nombre o una IP, no una URL: es frecuente pegar "http://proxy:3128" por error.
    private fun isValidHost(host: String): Boolean =
        host.isNotEmpty() && host.none { it.isWhitespace() || it in FORBIDDEN_HOST_CHARS }

    private companion object {
        val PORT_RANGE = 1..65535
        const val FORBIDDEN_HOST_CHARS = "/@?#"
    }
}
