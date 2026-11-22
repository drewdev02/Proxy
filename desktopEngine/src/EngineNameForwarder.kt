package org.uci.proxy.desktop

import org.uci.proxy.core.model.NameOutcome
import org.uci.proxy.core.port.NameServiceReporter

/**
 * Del lado del motor: lo que el túnel cuenta de los servicios de nombres no se puede mostrar aquí (la pantalla de Ajustes
 * está en la ventana, otro proceso), así que se manda por el socket. Hasta que haya un socket ([sink]) no hace nada.
 */
class EngineNameForwarder : NameServiceReporter {
    @Volatile
    var sink: ((String) -> Unit)? = null

    override fun report(serviceId: String, outcome: NameOutcome) {
        sink?.invoke(EngineProtocol.encodeNameStat(serviceId, outcome))
    }
}
