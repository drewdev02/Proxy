package org.uci.proxy.connection

import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.model.ProxyProtocol
import org.uci.proxy.core.model.RuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConnectionFormatTest {
    @Test
    fun `connected time is shown as hours minutes and seconds`() {
        assertEquals("00:00:00", formatElapsed(0.seconds))
        assertEquals("01:23:45", formatElapsed(1.hours + 23.minutes + 45.seconds))
    }

    @Test
    fun `hours keep counting past a day`() {
        assertEquals("49:00:05", formatElapsed(2.days + 1.hours + 5.seconds))
    }

    @Test
    fun `the address includes the user only when there is one`() {
        val profile = ProxyProfile("p1", "UCI", "proxy.uci.cu", 8080, ProxyProtocol.HTTP, "ana")

        assertEquals("ana@proxy.uci.cu:8080", profile.address())
        assertEquals("proxy.uci.cu:8080", profile.copy(username = "").address())
    }

    @Test
    fun `rule counts keep a fixed order and skip empty kinds`() {
        assertEquals(listOf(RuleKind.DOMAIN to 1), mapOf(RuleKind.DOMAIN to 1).inOrder())
        assertEquals(
            listOf(RuleKind.DOMAIN to 2, RuleKind.IP to 1, RuleKind.APP to 3),
            mapOf(RuleKind.APP to 3, RuleKind.DOMAIN to 2, RuleKind.IP to 1).inOrder(),
        )
    }

    @Test
    fun `the via proxy warning names the latest sites and counts the rest`() {
        assertEquals(ViaProxyHosts(listOf("a.com"), 0), viaProxyHosts(listOf("a.com")))
        assertEquals(
            ViaProxyHosts(listOf("e.com", "d.com", "c.com"), 2),
            viaProxyHosts(listOf("a.com", "b.com", "c.com", "d.com", "e.com")),
        )
    }
}
