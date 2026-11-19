package org.uci.proxy.core.port

/**
 * Dice qué app abrió una conexión que pasa por la interfaz, para aplicar las reglas de salida por app.
 *
 * Puede bloquear unos milisegundos (en Android es una llamada al sistema): se consulta una sola vez por conexión.
 */
fun interface ConnectionOwner {
    /**
     * @param protocol 6 (TCP) o 17 (UDP).
     * @return los paquetes de la app dueña (varios si comparten usuario), o vacío si no se sabe.
     */
    fun appsFor(protocol: Int, source: Int, sourcePort: Int, destination: Int, destinationPort: Int): List<String>

    companion object {
        /** Donde no se puede saber (escritorio, iOS): las reglas de app no coinciden nunca. */
        val Unknown = ConnectionOwner { _, _, _, _, _ -> emptyList() }
    }
}
