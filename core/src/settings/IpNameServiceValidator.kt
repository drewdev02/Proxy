package org.uci.proxy.core.settings

enum class ServiceError {
    /** Falta el nombre con el que se muestra. */
    NAME_EMPTY,

    /** La plantilla no lleva `{ip}` ni `{ip-}`: daría el mismo nombre para cualquier IP. */
    NO_PLACEHOLDER,

    /** Con una IP puesta, la plantilla no da un nombre de dominio válido. */
    INVALID_HOST,

    /** El dominio es local o reservado (`.local`, `.lan`…): el proxy no lo resolvería a una IP pública. */
    LOCAL_DOMAIN,

    /** Ya hay un servicio con esa plantilla. */
    DUPLICATE,
}

sealed interface ServiceValidation {
    data class Valid(val service: IpNameService) : ServiceValidation

    data class Invalid(val errors: Set<ServiceError>) : ServiceValidation
}

/** Comprueba un servicio de nombres escrito a mano antes de guardarlo. */
class IpNameServiceValidator {
    /** @param existing los servicios que ya hay, para no repetir plantilla. */
    fun validate(id: String, name: String, template: String, existing: List<IpNameService>): ServiceValidation {
        val cleanName = name.trim()
        val cleanTemplate = template.trim().lowercase()

        val errors = buildSet {
            if (cleanName.isEmpty() || cleanName.length > MAX_NAME) add(ServiceError.NAME_EMPTY)
            if (IpNameService.PLAIN !in cleanTemplate && IpNameService.DASHED !in cleanTemplate) add(ServiceError.NO_PLACEHOLDER)
            if (cleanTemplate.isNotEmpty() && (IpNameService.PLAIN in cleanTemplate || IpNameService.DASHED in cleanTemplate)) {
                val host = IpNameService(id, cleanName, cleanTemplate).nameFor(SAMPLE_IP)
                if (!isValidHost(host)) {
                    add(ServiceError.INVALID_HOST)
                } else if (host.substringAfterLast('.') in RESERVED_SUFFIXES) {
                    add(ServiceError.LOCAL_DOMAIN)
                }
            }
            if (existing.any { it.template.trim().lowercase() == cleanTemplate }) add(ServiceError.DUPLICATE)
        }
        if (errors.isNotEmpty()) return ServiceValidation.Invalid(errors)
        return ServiceValidation.Valid(IpNameService(id = id, name = cleanName, template = cleanTemplate))
    }

    /** Un nombre de dominio con al menos dos etiquetas de letras, cifras y guiones, que no termine en un número. */
    private fun isValidHost(host: String): Boolean {
        if (host.length > MAX_HOST) return false
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        if (!labels.all { LABEL.matches(it) }) return false
        // Un nombre que termina en cifras es una IP (o parece serlo): no es un dominio.
        return !labels.last().all { it.isDigit() }
    }

    private companion object {
        const val SAMPLE_IP = "1.2.3.4"
        const val MAX_NAME = 40
        const val MAX_HOST = 253
        val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        val RESERVED_SUFFIXES = setOf(
            "local", "localhost", "internal", "lan", "home", "corp", "intranet", "private", "test", "invalid", "example", "arpa",
        )
    }
}
