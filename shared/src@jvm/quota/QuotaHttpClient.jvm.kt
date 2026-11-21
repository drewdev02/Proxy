package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

// El cliente de Java (java.net.http): ya viene en el JDK, así que el .exe no lleva otra librería HTTP. Sin proxy salvo
// que se configure uno, que aquí no se hace: el servicio es de la intranet.
internal actual fun quotaHttpClient(verifyCertificate: Boolean): HttpClient = HttpClient(Java) {
    quotaDefaults()
    engine {
        if (!verifyCertificate) config { sslContext(TrustAll.sslContext) }
    }
}
