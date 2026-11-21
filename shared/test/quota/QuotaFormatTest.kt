package org.uci.proxy.quota

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaFormatTest {
    private fun quota(used: Double, assigned: Long = 2000) = UciQuota(assigned, used, "", 0)

    @Test
    fun `percent and remaining also past the quota`() {
        assertEquals(71.0, quota(1420.0).percent)
        assertEquals(115.0, quota(2300.0).percent)
        assertEquals(0.0, quota(2500.0).remainingMb)
        assertEquals("71 %", percentText(quota(1420.53)))
        assertEquals("1420,5 MB", mbText(1420.53))
        assertEquals("12 MB", mbText(12.0))
    }
}
