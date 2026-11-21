package org.uci.proxy.database.traffic

import androidx.room.Entity

/**
 * El consumo de un día por salida, destino y app, ya sumado. Lo desconocido va como texto vacío (no null) para que
 * forme parte de la clave: un destino sin app y el mismo con app son filas distintas.
 *
 * @param day el día local contado desde el 1 de enero de 1970.
 * @param exit la salida por su nombre (RouteExit).
 */
@Entity(tableName = "traffic_usage", primaryKeys = ["day", "exit", "host", "app"])
data class TrafficUsageRecord(
    val day: Int,
    val exit: String,
    val host: String,
    val app: String,
    val up: Long,
    val down: Long,
)

/** Una fila de las consultas que suman por día y salida. */
data class DailyTotalRow(val day: Int, val exit: String, val up: Long, val down: Long)

/** Una fila de las consultas que suman por destino o por app. */
data class NamedTotalRow(val name: String, val up: Long, val down: Long)
