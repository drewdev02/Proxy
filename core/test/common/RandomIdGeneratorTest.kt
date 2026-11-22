package org.uci.proxy.core.common

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomIdGeneratorTest {
    @Test
    fun `ids are 32 lowercase hex characters`() {
        val id = RandomIdGenerator(Random(seed = 1)).newId()

        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `consecutive ids differ`() {
        val generator = RandomIdGenerator(Random(seed = 1))

        assertTrue(generator.newId() != generator.newId())
    }
}
