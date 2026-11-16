package org.uci.proxy.core.profile

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatchers inyectados, para que las pruebas controlen dónde corre el trabajo. */
class AppDispatchers(
    /** Para trabajo que bloquea poco tiempo: leer y escribir en los almacenes locales. */
    val background: CoroutineDispatcher,
)
