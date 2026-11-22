package org.uci.proxy.core.model

/**
 * Una línea del registro. [id] crece de uno en uno dentro de un `LogBuffer` y sirve para saber qué es nuevo y como clave
 * de la lista en pantalla; no tiene sentido fuera del búfer que lo asignó. Las líneas recuperadas de una sesión anterior
 * (`LogBuffer.restore`) llevan ids negativos.
 */
data class LogEntry(
    val id: Long,
    val epochMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
