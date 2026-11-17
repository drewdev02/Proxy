package org.uci.proxy.core.profile

import kotlin.random.Random

fun interface IdGenerator {
    /** Identificador nuevo, formado solo por caracteres [0-9a-f] para que sirva como clave de almacén. */
    fun newId(): String
}

class RandomIdGenerator(private val random: Random = Random.Default) : IdGenerator {
    override fun newId(): String =
        random.nextBytes(ID_BYTES).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val ID_BYTES = 16
    }
}
