package org.uci.proxy.common

import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficFormatTest {
    @Test
    fun `sizes use base 1024 a decimal comma and three significant digits`() {
        assertEquals("0" to "B", formatBytes(0))
        assertEquals("512" to "B", formatBytes(512))
        assertEquals("1,5" to "KB", formatBytes(1_536))
        assertEquals("4,8" to "MB", formatBytes(5_033_165))
        assertEquals("234" to "KB", formatBytes(239_616))
        assertEquals("1,42" to "GB", formatBytes(1_524_713_390))
    }

    @Test
    fun `a value that rounds to 1024 moves up a unit`() {
        assertEquals("1" to "MB", formatBytes(1_048_575))
    }

    @Test
    fun `rates and plain text read naturally`() {
        assertEquals("820" to "KB/s", formatRate(839_680))
        assertEquals("1,29 GB", bytesText(1_385_126_952))
    }
}
