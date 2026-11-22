package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

@OptIn(ExperimentalForeignApi::class)
internal actual fun quotaHttpClient(verifyCertificate: Boolean): HttpClient = HttpClient(Darwin) {
    quotaDefaults()
    engine {
        // Sin el proxy del sistema: el servicio es de la intranet.
        configureSession { connectionProxyDictionary = emptyMap<Any?, Any?>() }
        if (!verifyCertificate) {
            handleChallenge { _, _, challenge, completionHandler ->
                val trust = challenge.protectionSpace.serverTrust
                if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust && trust != null) {
                    completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                } else {
                    completionHandler(NSURLSessionAuthChallengeUseCredential, null)
                }
            }
        }
    }
}

// NSURLErrorServerCertificate*: -1202 a -1206.
internal actual fun isCertificateError(error: Throwable): Boolean {
    val nsError = (error as? DarwinHttpRequestException)?.origin ?: return false
    return nsError.domain == NSURLErrorDomain && nsError.code in -1206L..-1202L
}
