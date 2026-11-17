package org.uci.proxy.core.packet

/**
 * Extrae el nombre del servidor de los primeros bytes de una conexión: la cabecera `Host` de una
 * petición HTTP o el SNI de un ClientHello de TLS.
 *
 * Sirve para las apps que conectan directamente a una IP: al proxy conviene pedirle el nombre.
 *
 * @return null si los bytes no son ninguno de los dos o no traen nombre.
 */
fun sniffHostname(data: ByteArray): String? {
    if (data.size <= 10) return null
    return when (data[0].toInt().toChar()) {
        // Inicial de los métodos HTTP: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, CONNECT.
        'G', 'H', 'P', 'D', 'O', 'C' -> httpHost(data)
        TLS_HANDSHAKE -> tlsServerName(data)
        else -> null
    }
}

private const val TLS_HANDSHAKE = 22.toChar()
private const val HTTP_SCAN_LIMIT = 2048
private const val EXTENSION_SERVER_NAME = 0

private fun httpHost(data: ByteArray): String? {
    // Las cabeceras son ASCII; decodificar como Latin-1 evita fallos con bytes sueltos del cuerpo.
    val text = CharArray(minOf(data.size, HTTP_SCAN_LIMIT)) { (data[it].toInt() and 0xFF).toChar() }.concatToString()
    val line = text.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) } ?: return null
    val host = line.substring(5).trim().substringBefore(':')
    return host.takeIf { it.isNotEmpty() }
}

/** Recorre el ClientHello hasta la extensión server_name. Cada salto se comprueba contra el tamaño real. */
private fun tlsServerName(data: ByteArray): String? {
    val limit = data.size
    // Registro (5) + cabecera del handshake (4) + versión (2) + aleatorio (32) = 43: ahí está la longitud del id de sesión.
    if (limit <= 43) return null
    val cipherSuites = 44 + (data[43].toInt() and 0xFF)
    if (cipherSuites + 2 > limit) return null
    val compressionMethods = cipherSuites + 2 + data.readU16(cipherSuites)
    if (compressionMethods + 1 > limit) return null
    val extensions = compressionMethods + 1 + (data[compressionMethods].toInt() and 0xFF)
    if (extensions + 2 > limit) return null

    var position = extensions + 2
    while (position + 4 <= limit) {
        val type = data.readU16(position)
        val length = data.readU16(position + 2)
        if (type == EXTENSION_SERVER_NAME) {
            // Lista (2) + tipo de nombre (1) + longitud del nombre (2), y después el nombre.
            val nameStart = position + 9
            // La extensión puede venir cortada si el ClientHello no cupo en el primer segmento.
            if (length <= 5 || nameStart > limit) return null
            val nameLength = data.readU16(position + 7)
            if (nameLength == 0 || nameStart + nameLength > limit) return null
            return CharArray(nameLength) { (data[nameStart + it].toInt() and 0xFF).toChar() }.concatToString()
        }
        position += 4 + length
    }
    return null
}
