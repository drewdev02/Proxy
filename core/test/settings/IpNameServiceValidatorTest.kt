package org.uci.proxy.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IpNameServiceValidatorTest {
    private val validator = IpNameServiceValidator()

    private fun validate(name: String = "Mío", template: String, existing: List<IpNameService> = IpNameService.BUILT_IN) =
        validator.validate("custom-1", name, template, existing)

    private fun errors(name: String = "Mío", template: String, existing: List<IpNameService> = IpNameService.BUILT_IN): Set<ServiceError> =
        (validate(name, template, existing) as ServiceValidation.Invalid).errors

    @Test
    fun `a reasonable template is accepted and normalized`() {
        val result = validate(name = "  Mi servicio  ", template = "  {IP-}.Mi-Dominio.com ")

        val service = assertIs<ServiceValidation.Valid>(result).service
        assertEquals("Mi servicio", service.name)
        assertEquals("{ip-}.mi-dominio.com", service.template, "sin espacios y en minúsculas")
        assertEquals("custom-1", service.id)
        assertEquals(false, service.builtIn)
        assertTrue(service.enabled)
    }

    @Test
    fun `both placeholders work and can even be combined`() {
        assertIs<ServiceValidation.Valid>(validate(template = "{ip}.mio.com"))
        assertIs<ServiceValidation.Valid>(validate(template = "ip-{ip-}.mio.com"))
        assertIs<ServiceValidation.Valid>(validate(template = "{ip-}.{ip}.mio.com"))
    }

    @Test
    fun `a name is required`() {
        assertEquals(setOf(ServiceError.NAME_EMPTY), errors(name = "   ", template = "{ip}.mio.com"))
        assertEquals(setOf(ServiceError.NAME_EMPTY), errors(name = "x".repeat(41), template = "{ip}.mio.com"))
    }

    @Test
    fun `a template without the IP would give the same name for everything`() {
        assertEquals(setOf(ServiceError.NO_PLACEHOLDER), errors(template = "fijo.mio.com"))
        assertEquals(setOf(ServiceError.NO_PLACEHOLDER), errors(template = ""))
    }

    @Test
    fun `things that are not a domain name are rejected`() {
        listOf(
            "{ip}", // sin dominio
            "{ip}.", // etiqueta vacía
            "{ip}..mio.com",
            "http://{ip}.mio.com",
            "{ip}.mio.com/ruta",
            "{ip}@mio.com",
            "{ip} .mio.com",
            "{ip}.mio.com:8080",
            "{ip}.mío.com",
            "-{ip-}.mio.com",
            "{ip-}-.mio.com",
            "{ip}.mio.123", // termina en número: parece una IP
            "{ip}." + "a".repeat(64) + ".com",
        ).forEach { assertEquals(setOf(ServiceError.INVALID_HOST), errors(template = it), it) }
    }

    @Test
    fun `local and reserved domains are rejected`() {
        listOf("{ip}.mio.local", "{ip}.localhost", "{ip}.empresa.lan", "{ip}.red.internal", "{ip}.casa.home", "{ip}.mio.test", "{ip}.mio.example", "{ip}.in-addr.arpa")
            .forEach { assertEquals(setOf(ServiceError.LOCAL_DOMAIN), errors(template = it), it) }
    }

    @Test
    fun `the same template cannot be added twice whatever its case`() {
        assertEquals(setOf(ServiceError.DUPLICATE), errors(template = "{ip-}.sslip.io"))
        assertEquals(setOf(ServiceError.DUPLICATE), errors(template = " {IP}.NIP.IO "))
    }

    @Test
    fun `several problems are reported together`() {
        assertEquals(setOf(ServiceError.NAME_EMPTY, ServiceError.NO_PLACEHOLDER), errors(name = "", template = "fijo.mio.com"))
    }
}
