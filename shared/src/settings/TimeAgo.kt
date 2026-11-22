package org.uci.proxy.settings

/**
 * Cuánto hace de algo, redondeado como se dice: «hace unos segundos», «hace 5 min», «hace 3 h», «hace 2 d». Es un tipo y no
 * un texto para que la pantalla escriba la frase completa en el idioma de la persona.
 */
sealed interface TimeAgo {
    data object Seconds : TimeAgo

    data class Minutes(val count: Int) : TimeAgo

    data class Hours(val count: Int) : TimeAgo

    data class Days(val count: Int) : TimeAgo
}

/** Cuánto hace de [thenMillis] visto desde [nowMillis]. Una marca de tiempo en el futuro cuenta como ahora. */
fun timeAgo(thenMillis: Long, nowMillis: Long): TimeAgo {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 45 -> TimeAgo.Seconds
        seconds < 3_600 -> TimeAgo.Minutes(maxOf(1, seconds / 60).toInt())
        seconds < 86_400 -> TimeAgo.Hours((seconds / 3_600).toInt())
        else -> TimeAgo.Days((seconds / 86_400).toInt())
    }
}
