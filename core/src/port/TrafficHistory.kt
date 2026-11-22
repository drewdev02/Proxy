package org.uci.proxy.core.port

import org.uci.proxy.core.model.DailyTraffic
import org.uci.proxy.core.model.NamedTraffic
import org.uci.proxy.core.model.TrafficUsage
import kotlinx.coroutines.flow.Flow

/** El consumo guardado en disco, sumado por día, salida, destino y app. */
interface TrafficHistory {
    /** Suma [usage] a lo que ya hubiera de [day]. */
    suspend fun add(day: Int, usage: List<TrafficUsage>)

    /** Lo de cada día y salida desde [fromDay] (incluido). */
    fun daily(fromDay: Int): Flow<List<DailyTraffic>>

    /** Los destinos que más consumieron desde [fromDay], de más a menos. */
    fun topHosts(fromDay: Int, limit: Int): Flow<List<NamedTraffic>>

    /** Las apps que más consumieron desde [fromDay], de más a menos; solo donde se sabe la app (Android). */
    fun topApps(fromDay: Int, limit: Int): Flow<List<NamedTraffic>>

    /** Borra lo anterior a [day]. */
    suspend fun deleteBefore(day: Int)
}
