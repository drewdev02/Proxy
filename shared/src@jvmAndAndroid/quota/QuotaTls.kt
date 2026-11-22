package org.uci.proxy.quota

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509ExtendedTrustManager

internal actual fun isCertificateError(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is SSLPeerUnverifiedException || it is CertPathValidatorException || it is CertificateException ||
        (it is SSLHandshakeException && it.message.orEmpty().contains("certif", ignoreCase = true))
}

/**
 * Acepta cualquier certificado y cualquier nombre. Solo si la persona lo pidió en Ajustes (QuotaSettings.verifyCertificate).
 * Es «extendido» a propósito: con uno simple, Java seguiría comprobando el nombre del servidor por su cuenta.
 */
internal object TrustAll : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    val sslContext: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) } }
}
