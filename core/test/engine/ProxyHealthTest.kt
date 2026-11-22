package org.uci.proxy.core.engine

import org.uci.proxy.core.port.ProxyProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.TestTimeSource

class ProxyHealthTest {
    private val health = ProxyHealth(TestTimeSource())

    @Test
    fun `one failed connection is not a problem yet`() {
        health.markAuthRejected()
        health.markUnreachable()

        assertNull(health.problem)
    }

    @Test
    fun `three rejected credentials in a row are a problem`() {
        repeat(3) { health.markAuthRejected() }

        assertEquals(ProxyProblem.AUTH_REJECTED, health.problem)
    }

    @Test
    fun `three unreachable attempts in a row are a problem`() {
        repeat(3) { health.markUnreachable() }

        assertEquals(ProxyProblem.UNREACHABLE, health.problem)
    }

    @Test
    fun `a connection that works clears the problem`() {
        repeat(3) { health.markAuthRejected() }

        health.markSuccess()

        assertNull(health.problem)
    }
}
