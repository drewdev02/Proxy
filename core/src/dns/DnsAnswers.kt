package org.uci.proxy.core.dns

import org.uci.proxy.core.packet.readInt
import org.uci.proxy.core.packet.readU16

/**
 * Las direcciones IPv4 (registros A) de una respuesta DNS real, la que da el servidor de la VPN.
 *
 * @return vacío si [message] no es una respuesta o no trae registros A.
 */
fun dnsAnswerAddresses(message: ByteArray): List<Int> {
    if (message.size < HEADER_SIZE || message.readU16(2) and FLAG_RESPONSE == 0) return emptyList()
    val questions = message.readU16(4)
    val answers = message.readU16(6)
    var position = HEADER_SIZE
    repeat(questions) {
        position = skipName(message, position) ?: return emptyList()
        position += 4 // tipo y clase
    }
    val addresses = mutableListOf<Int>()
    repeat(answers) {
        position = skipName(message, position) ?: return addresses
        if (position + 10 > message.size) return addresses
        val type = message.readU16(position)
        val dataLength = message.readU16(position + 8)
        val data = position + 10
        if (data + dataLength > message.size) return addresses
        if (type == DnsResponder.TYPE_A && dataLength == 4) addresses += message.readInt(data)
        position = data + dataLength
    }
    return addresses
}

/** El identificador de la transacción de un mensaje DNS, o null si es demasiado corto. */
fun dnsTransactionId(message: ByteArray): Int? = if (message.size < HEADER_SIZE) null else message.readU16(0)

/** La posición que sigue a un nombre, con o sin compresión. */
private fun skipName(message: ByteArray, start: Int): Int? {
    var position = start
    while (position < message.size) {
        val length = message[position].toInt() and 0xFF
        when {
            length == 0 -> return position + 1
            // Un puntero de compresión ocupa dos bytes y termina el nombre.
            length and 0xC0 == 0xC0 -> return if (position + 2 <= message.size) position + 2 else null
            else -> position += 1 + length
        }
    }
    return null
}

private const val HEADER_SIZE = 12
private const val FLAG_RESPONSE = 0x8000

/** Una consulta DNS de tipo A por [name], con recursión, para preguntar a un servidor real. */
fun buildDnsQuery(name: String, id: Int): ByteArray {
    val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }.map { it.encodeToByteArray() }
    val message = ByteArray(HEADER_SIZE + labels.sumOf { it.size + 1 } + 1 + 4)
    message[0] = (id ushr 8).toByte()
    message[1] = id.toByte()
    message[2] = 0x01 // recursión deseada
    message[5] = 1 // una pregunta
    var position = HEADER_SIZE
    for (label in labels) {
        message[position++] = label.size.toByte()
        label.copyInto(message, position)
        position += label.size
    }
    message[position++] = 0
    message[position + 1] = DnsResponder.TYPE_A.toByte()
    message[position + 3] = 1 // clase IN
    return message
}
