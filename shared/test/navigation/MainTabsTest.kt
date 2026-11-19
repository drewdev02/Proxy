package org.uci.proxy.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsTest {
    @Test
    fun `another tab sits on top of connection so back returns there`() {
        val stack = mutableListOf<NavKey>(Route.Connection)

        stack.selectTab(Route.Settings)

        assertEquals(listOf<NavKey>(Route.Connection, Route.Settings), stack)
    }

    @Test
    fun `choosing connection leaves only connection`() {
        val stack = mutableListOf<NavKey>(Route.Connection, Route.Logs)

        stack.selectTab(Route.Connection)

        assertEquals(listOf<NavKey>(Route.Connection), stack)
    }

    @Test
    fun `changing tab closes the detail screens that were open`() {
        val stack = mutableListOf<NavKey>(Route.Connection, Route.ProfileList, Route.ProfileEditor("p1"))

        stack.selectTab(Route.Logs)

        assertEquals(listOf<NavKey>(Route.Connection, Route.Logs), stack)
    }

    @Test
    fun `a detail screen belongs to the tab below it and hides the bar`() {
        val stack = listOf<NavKey>(Route.Connection, Route.ProfileList, Route.ProfileEditor("p1"))

        assertEquals(Route.ProfileList, stack.currentTab())
        assertFalse(stack.isOnTab())
        assertTrue(stack.dropLast(1).isOnTab())
    }

    @Test
    fun `the editor opened from connection belongs to connection`() {
        assertEquals(Route.Connection, listOf<NavKey>(Route.Connection, Route.ProfileEditor(null)).currentTab())
    }

    @Test
    fun `only tabs can be selected`() {
        assertFailsWith<IllegalArgumentException> { mutableListOf<NavKey>().selectTab(Route.Catalog) }
    }
}
