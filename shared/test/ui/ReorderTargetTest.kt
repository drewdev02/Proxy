package org.uci.proxy.ui

import org.uci.proxy.theme.ProxyUciColors
import org.uci.proxy.ui.components.reorderTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTargetTest {
    private val heights = listOf(100, 100, 100, 100)
    private val spacing = 10f

    @Test
    fun `a short drag keeps the row in place`() {
        assertEquals(1, reorderTarget(from = 1, offset = 40f, heights = heights, spacing = spacing))
        assertEquals(1, reorderTarget(from = 1, offset = -40f, heights = heights, spacing = spacing))
    }

    @Test
    fun `passing half of the next row swaps with it`() {
        assertEquals(2, reorderTarget(from = 1, offset = 60f, heights = heights, spacing = spacing))
        assertEquals(0, reorderTarget(from = 1, offset = -60f, heights = heights, spacing = spacing))
    }

    @Test
    fun `a long drag skips several rows`() {
        assertEquals(3, reorderTarget(from = 0, offset = 300f, heights = heights, spacing = spacing))
    }

    @Test
    fun `the target never leaves the list`() {
        assertEquals(3, reorderTarget(from = 2, offset = 5_000f, heights = heights, spacing = spacing))
        assertEquals(0, reorderTarget(from = 2, offset = -5_000f, heights = heights, spacing = spacing))
    }

    @Test
    fun `rows of different heights use the height of the row being passed`() {
        val mixed = listOf(40, 200, 40)
        // Para pasar la fila alta (200 + 10 de separación) hay que superar 105 px.
        assertEquals(0, reorderTarget(from = 0, offset = 100f, heights = mixed, spacing = spacing))
        assertEquals(1, reorderTarget(from = 0, offset = 110f, heights = mixed, spacing = spacing))
    }
}

class LatencyColorTest {
    private val colors = ProxyUciColors()

    @Test
    fun `latency uses the three tiers of the design`() {
        assertEquals(colors.latencyGood, colors.latency(0))
        assertEquals(colors.latencyGood, colors.latency(49))
        assertEquals(colors.latencyFair, colors.latency(50))
        assertEquals(colors.latencyFair, colors.latency(150))
        assertEquals(colors.latencyPoor, colors.latency(151))
    }
}
