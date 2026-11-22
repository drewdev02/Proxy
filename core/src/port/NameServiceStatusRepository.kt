package org.uci.proxy.core.port

import org.uci.proxy.core.model.NameOutcome
import org.uci.proxy.core.model.NameServiceStatus
import kotlinx.coroutines.flow.StateFlow

/** Quien se entera de cómo le fue a cada servicio: el túnel avisa y la pantalla de Ajustes lo muestra. */
fun interface NameServiceReporter {
    fun report(serviceId: String, outcome: NameOutcome)

    companion object {
        val None = NameServiceReporter { _, _ -> }
    }
}

interface NameServiceStatusRepository : NameServiceReporter {
    /** El último resultado de cada servicio, por id. */
    val statuses: StateFlow<Map<String, NameServiceStatus>>
}
