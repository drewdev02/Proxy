package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy

internal actual fun quotaHttpClient(verifyCertificate: Boolean): HttpClient = HttpClient(OkHttp) {
    quotaDefaults()
    engine {
        // Sin proxy aunque el sistema tenga uno: el servicio es de la intranet.
        proxy = Proxy.NO_PROXY
        if (!verifyCertificate) {
            config {
                sslSocketFactory(TrustAll.sslContext.socketFactory, TrustAll)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}
