package org.uci.proxy.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class QuotaFormatTest {
    private fun quota(used: Double, assigned: Long = 2000) = UciQuota(assigned, used, "", 0)

    @Test
    fun `percent and remaining also past the quota`() {
        assertEquals(71.0, quota(1420.0).percent)
        assertEquals(115.0, quota(2300.0).percent)
        assertEquals(0.0, quota(2500.0).remainingMb)
        assertEquals(71, quota(1420.53).wholePercent)
        assertEquals("1420,5 MB", mbText(1420.53, ','))
        assertEquals("1420.5 MB", mbText(1420.53, '.'))
        assertEquals("12 MB", mbText(12.0, ','))
    }

    @Test
    fun `when it was checked uses the largest whole unit`() {
        assertEquals(CheckedAgo.Now, checkedAgo(30.seconds))
        assertEquals(CheckedAgo.Minutes(3), checkedAgo(3.minutes + 20.seconds))
        assertEquals(CheckedAgo.Hours(2), checkedAgo(2.hours + 59.minutes))
        assertEquals(CheckedAgo.Days(4), checkedAgo(4.days + 1.hours))
    }
}
