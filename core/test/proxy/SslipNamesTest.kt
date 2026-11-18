package org.uci.proxy.core.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SslipNamesTest {
    @Test
    fun `a public IP becomes the sslip name that resolves to it`() {
        assertEquals("149-22-82-28.sslip.io", sslipNameFor("149.22.82.28"))
        assertEquals("8-8-8-8.sslip.io", sslipNameFor("8.8.8.8"))
    }

    @Test
    fun `private and reserved IPs are left alone`() {
        listOf("10.0.0.1", "10.98.0.1", "172.16.5.4", "192.168.1.10", "127.0.0.1", "169.254.1.1", "100.64.0.1", "224.0.0.1").forEach {
            assertNull(sslipNameFor(it), it)
        }
    }

    @Test
    fun `names and things that are not an IP are left alone`() {
        listOf("example.com", "149-22-82-28.sslip.io", "149.22.82", "149.22.82.28.5", "999.1.1.1", "", "::1").forEach {
            assertNull(sslipNameFor(it), it)
        }
    }
}
