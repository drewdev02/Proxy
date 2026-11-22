package org.uci.proxy.core.model

/** Lo que salió por [exit] un día. [day] es el día local contado desde el 1 de enero de 1970. */
data class DailyTraffic(val day: Int, val exit: RouteExit, val up: Long, val down: Long) {
    val total: Long get() = up + down
}

/** Lo consumido por un destino o una app. [name] vacío: sin detalle (todo por Otro País, sin reglas). */
data class NamedTraffic(val name: String, val up: Long, val down: Long) {
    val total: Long get() = up + down
}
