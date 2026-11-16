package org.uci.proxy.core

import org.uci.proxy.core.port.PlatformInfo
import org.uci.proxy.core.port.PlatformKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformInfoTest {
    private fun infoOf(kind: PlatformKind) = object : PlatformInfo {
        override val kind = kind
        override val osVersion = "1.0"
    }

    @Test
    fun `only Windows macOS and Linux are desktop`() {
        val desktop = PlatformKind.entries.filter { infoOf(it).isDesktop }.toSet()

        assertEquals(setOf(PlatformKind.WINDOWS, PlatformKind.MACOS, PlatformKind.LINUX), desktop)
    }
}
