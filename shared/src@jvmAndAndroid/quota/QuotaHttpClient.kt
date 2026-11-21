package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

internal actual fun quotaHttpClient(verifyCertificate: Boolean): HttpClient = HttpClient(OkHttp) {
    quotaDefaults()
    engine {
        // Sin proxy aunque el sistema tenga uno: el servicio es de la intranet.
        proxy = Proxy.NO_PROXY
        if (!verifyCertificate) {
            config {
                val trustAll = TrustAll()
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
                sslSocketFactory(context.socketFactory, trustAll)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}

internal actual fun isCertificateError(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is SSLPeerUnverifiedException || it is CertPathValidatorException || it is CertificateException ||
        (it is SSLHandshakeException && it.message.orEmpty().contains("certif", ignoreCase = true))
}

/** Acepta cualquier certificado. Solo si la persona lo pidió en Ajustes (ver QuotaSettings.verifyCertificate). */
private class TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
