package org.uci.proxy.quota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class QuotaCheckedAgoTest {
    @Test
    fun `when it was checked uses the largest whole unit`() {
        assertEquals(CheckedAgo.Now, checkedAgo(30.seconds))
        assertEquals(CheckedAgo.Minutes(3), checkedAgo(3.minutes + 20.seconds))
        assertEquals(CheckedAgo.Hours(2), checkedAgo(2.hours + 59.minutes))
        assertEquals(CheckedAgo.Days(4), checkedAgo(4.days + 1.hours))
    }
}
