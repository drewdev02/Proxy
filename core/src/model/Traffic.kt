package org.uci.proxy.core.model

import kotlinx.serialization.Serializable

/**
 * Bytes de una salida hacia un destino en un intervalo.
 *
 * @param host el nombre (o la IP) del destino; null cuando no se sabe (todo por Otro País, sin reglas).
 * @param app la app que abrió la conexión, donde el sistema lo dice (Android, y solo para lo que va por el proxy).
 * @param up de las apps hacia fuera; [down] al revés.
 */
@Serializable
data class TrafficUsage(val exit: RouteExit, val host: String?, val app: String?, val up: Long, val down: Long)
