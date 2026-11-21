package org.uci.proxy.core.profile

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UciQuotaProfileTest {
    private fun profile(host: String, user: String = "ana", uciQuota: Boolean? = null) =
        ProxyProfile("p", "P", host, 3128, ProxyProtocol.HTTP, user, uciQuota = uciQuota)

    @Test
    fun `a uci proxy checks the quota unless switched off`() {
        assertTrue(profile("proxy.uci.cu").checksUciQuota)
        assertTrue(profile("UCI.CU").checksUciQuota)
        assertFalse(profile("proxy.uci.cu", uciQuota = false).checksUciQuota)
        assertFalse(profile("10.0.0.1").checksUciQuota)
        assertTrue(profile("10.0.0.1", uciQuota = true).checksUciQuota)
        assertFalse(profile("notuci.cu").checksUciQuota)
    }

    @Test
    fun `without a user there is nothing to check`() {
        assertFalse(profile("proxy.uci.cu", user = " ").checksUciQuota)
    }

    @Test
    fun `the account goes without the domain`() {
        assertEquals("ana", profile("proxy.uci.cu", user = "UCI\\ana").uciAccount)
        assertEquals("ana", profile("proxy.uci.cu", user = "ana@uci.cu").uciAccount)
        assertEquals("ana.perez", profile("proxy.uci.cu", user = " ana.perez ").uciAccount)
    }
}
