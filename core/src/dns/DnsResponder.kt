package org.uci.proxy.core.dns

import org.uci.proxy.core.packet.readU16
import org.uci.proxy.core.packet.writeInt
import org.uci.proxy.core.packet.writeU16

/** Una consulta DNS ya interpretada. */
data class DnsQuestion(val name: String, val type: Int)

/**
 * Responde consultas DNS con direcciones de la [FakeDnsTable], sin consultar a nadie.
 *
 * - Tipo A: una respuesta con la dirección inventada del nombre.
 * - Cualquier otro tipo (AAAA, HTTPS, SRV…): respuesta vacía sin error, para que el sistema
 *   use enseguida el registro A y el tráfico entre al túnel por IPv4.
 */
class DnsResponder(
    private val table: FakeDnsTable,
    /** Nombres con una dirección propia en vez de la inventada: los que el sistema usa para comprobar la conectividad. */
    private val addressOverride: (String) -> Int? = { null },
) {
    /**
     * @param query mensaje DNS tal como llega en el datagrama UDP.
     * @return el mensaje de respuesta, o null si [query] no es una consulta válida.
     */
    fun respond(query: ByteArray): ByteArray? {
        val question = parseQuestion(query) ?: return null
        val questionLength = question.second
        val includeAnswer = question.first.type == TYPE_A

        val response = ByteArray(HEADER_SIZE + questionLength + if (includeAnswer) ANSWER_SIZE else 0)
        query.copyInto(response, 0, 0, 2) // identificador de la transacción
        // Respuesta, sin error, recursión disponible; se conserva el bit "recursión deseada" de la consulta.
        response.writeU16(2, FLAG_RESPONSE or FLAG_RECURSION_AVAILABLE or (query.readU16(2) and FLAG_RECURSION_DESIRED))
        response.writeU16(4, 1) // una pregunta
        response.writeU16(6, if (includeAnswer) 1 else 0)
        query.copyInto(response, HEADER_SIZE, HEADER_SIZE, HEADER_SIZE + questionLength)

        if (includeAnswer) {
            val answer = HEADER_SIZE + questionLength
            response.writeU16(answer, POINTER_TO_QUESTION_NAME)
            response.writeU16(answer + 2, TYPE_A)
            response.writeU16(answer + 4, CLASS_IN)
            response.writeInt(answer + 6, TTL_SECONDS)
            response.writeU16(answer + 10, 4)
            response.writeInt(answer + 12, addressOverride(question.first.name) ?: table.addressFor(question.first.name))
        }
        return response
    }

    /** Devuelve la primera pregunta y cuántos bytes ocupa, o null si el mensaje no es una consulta válida. */
    fun parseQuestion(query: ByteArray): Pair<DnsQuestion, Int>? {
        if (query.size < HEADER_SIZE) return null
        val flags = query.readU16(2)
        if (flags and FLAG_RESPONSE != 0 || query.readU16(4) == 0) return null

        val labels = mutableListOf<String>()
        var position = HEADER_SIZE
        while (true) {
            if (position >= query.size) return null
            val length = query[position].toInt() and 0xFF
            position++
            if (length == 0) break
            // En la pregunta no se esperan punteros de compresión (los dos bits altos a 1).
            if (length and 0xC0 != 0 || position + length > query.size) return null
            labels += query.decodeToString(position, position + length)
            position += length
        }
        if (labels.isEmpty() || position + 4 > query.size) return null
        if (query.readU16(position + 2) != CLASS_IN) return null

        val question = DnsQuestion(name = labels.joinToString("."), type = query.readU16(position))
        return question to (position + 4 - HEADER_SIZE)
    }

    companion object {
        const val TYPE_A = 1
        private const val CLASS_IN = 1
        private const val HEADER_SIZE = 12
        private const val ANSWER_SIZE = 16
        private const val FLAG_RESPONSE = 0x8000
        private const val FLAG_RECURSION_DESIRED = 0x0100
        private const val FLAG_RECURSION_AVAILABLE = 0x0080

        // 0xC00C: puntero de compresión al nombre de la pregunta, que empieza en el byte 12.
        private const val POINTER_TO_QUESTION_NAME = 0xC00C

        // Corto a propósito: la correspondencia solo vale mientras dure este túnel.
        private const val TTL_SECONDS = 60
    }
}
