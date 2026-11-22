package org.uci.proxy.logs

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.uci.proxy.core.model.LogEntry
import org.uci.proxy.core.model.LogLevel
import kotlin.time.Instant

/** La hora de un mensaje como `14:03:07.042`, en la zona horaria del equipo. */
fun formatLogTime(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${two(time.hour)}:${two(time.minute)}:${two(time.second)}.${(epochMillis.mod(1000L)).toString().padStart(3, '0')}"
}

/** El nombre del nivel como se muestra, de ancho fijo para que las columnas queden alineadas. */
fun LogLevel.label(): String = when (this) {
    LogLevel.DEBUG -> "DEBUG"
    LogLevel.INFO -> "INFO "
    LogLevel.WARN -> "AVISO"
    LogLevel.ERROR -> "ERROR"
}

/** Una línea de registro: `14:03:07.042 AVISO Relay: El proxy no abrió example.org:443`. */
fun LogEntry.toLine(zone: TimeZone = TimeZone.currentSystemDefault()): String =
    "${formatLogTime(epochMillis, zone)} ${level.label()} $tag: $message"

/** Los mensajes como texto, uno por línea, para copiarlos al portapapeles o pegarlos en un informe. */
fun List<LogEntry>.toText(zone: TimeZone = TimeZone.currentSystemDefault()): String =
    joinToString("\n") { it.toLine(zone) }
