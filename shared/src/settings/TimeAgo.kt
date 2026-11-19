package org.uci.proxy.settings

/** «hace unos segundos», «hace 5 min», «hace 3 h», «hace 2 d». Una marca de tiempo en el futuro cuenta como ahora. */
fun timeAgo(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 45 -> "hace unos segundos"
        seconds < 3_600 -> "hace ${maxOf(1, seconds / 60)} min"
        seconds < 86_400 -> "hace ${seconds / 3_600} h"
        else -> "hace ${seconds / 86_400} d"
    }
}
