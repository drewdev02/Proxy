package org.uci.proxy.core.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileValidatorTest {
    private val validator = ProfileValidator()
    private val validDraft = ProfileDraft(
        name = "UCI",
        host = "proxy.uci.cu",
        port = "3128",
        protocol = ProxyProtocol.HTTP,
        username = "andrey",
    )

    private fun errorsOf(draft: ProfileDraft): Set<ProfileField> =
        (validator.validate("id", draft) as? ProfileValidation.Invalid)?.errors.orEmpty()

    @Test
    fun `a complete draft becomes a profile`() {
        val expected = ProxyProfile("id-1", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey")

        assertEquals(ProfileValidation.Valid(expected), validator.validate("id-1", validDraft))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        val draft = validDraft.copy(name = " UCI ", host = " proxy.uci.cu ", port = " 3128 ", username = " andrey ")

        val result = validator.validate("id", draft) as ProfileValidation.Valid

        assertEquals(ProxyProfile("id", "UCI", "proxy.uci.cu", 3128, ProxyProtocol.HTTP, "andrey"), result.profile)
    }

    @Test
    fun `username is optional`() {
        assertEquals(emptySet(), errorsOf(validDraft.copy(username = "")))
    }

    @Test
    fun `an IP address is a valid host`() {
        assertEquals(emptySet(), errorsOf(validDraft.copy(host = "10.0.0.1")))
    }

    @Test
    fun `blank name is rejected`() {
        assertEquals(setOf(ProfileField.NAME), errorsOf(validDraft.copy(name = "  ")))
    }

    @Test
    fun `blank host is rejected`() {
        assertEquals(setOf(ProfileField.HOST), errorsOf(validDraft.copy(host = "")))
    }

    @Test
    fun `a pasted URL is rejected as host`() {
        assertEquals(setOf(ProfileField.HOST), errorsOf(validDraft.copy(host = "http://proxy.uci.cu")))
    }

    @Test
    fun `a host with spaces is rejected`() {
        assertEquals(setOf(ProfileField.HOST), errorsOf(validDraft.copy(host = "proxy uci")))
    }

    @Test
    fun `non numeric port is rejected`() {
        assertEquals(setOf(ProfileField.PORT), errorsOf(validDraft.copy(port = "abc")))
    }

    @Test
    fun `port zero and ports above 65535 are rejected`() {
        assertEquals(setOf(ProfileField.PORT), errorsOf(validDraft.copy(port = "0")))
        assertEquals(setOf(ProfileField.PORT), errorsOf(validDraft.copy(port = "65536")))
    }

    @Test
    fun `the port limits are accepted`() {
        assertEquals(emptySet(), errorsOf(validDraft.copy(port = "1")))
        assertEquals(emptySet(), errorsOf(validDraft.copy(port = "65535")))
    }

    @Test
    fun `every invalid field is reported at once`() {
        val errors = errorsOf(ProfileDraft())

        assertEquals(setOf(ProfileField.NAME, ProfileField.HOST, ProfileField.PORT), errors)
    }
}
