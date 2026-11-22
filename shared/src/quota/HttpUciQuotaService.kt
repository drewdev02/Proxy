package org.uci.proxy.quota

import org.uci.proxy.core.port.QuotaFetch
import org.uci.proxy.core.port.UciQuotaService
import org.uci.proxy.core.quota.QuotaSoap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * Un cliente HTTP que va siempre sin proxy (el servicio es de la intranet, como en UCIProxy) y, con [verifyCertificate]
 * en false, acepta cualquier certificado. Cada plataforma usa su motor: OkHttp en JVM y Android, Darwin en iOS.
 */
internal expect fun quotaHttpClient(verifyCertificate: Boolean): HttpClient

/** Si [error] es un fallo de verificación del certificado (cada plataforma lo lanza a su manera). */
internal expect fun isCertificateError(error: Throwable): Boolean

/** El servicio de verdad, por HTTPS. [client] solo cambia en las pruebas. */
class HttpUciQuotaService internal constructor(
    private val client: (verifyCertificate: Boolean) -> HttpClient,
) : UciQuotaService {
    constructor() : this(::quotaHttpClient)

    override suspend fun fetch(account: String, password: String, url: String, verifyCertificate: Boolean): QuotaFetch {
        val body = try {
            client(verifyCertificate).use { http ->
                http.post(url) {
                    contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
                    header("SOAPAction", "\"${QuotaSoap.SOAP_ACTION}\"")
                    setBody(QuotaSoap.request(account, password))
                }.bodyAsText()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            // El tiempo agotado de Ktor es una CancellationException en algunas plataformas; se trata arriba como cancelación
            // solo si de verdad se canceló quien llama.
            return if (isCertificateError(e)) QuotaFetch.UntrustedCertificate(message) else QuotaFetch.Unreachable(message)
        }
        // SOAP devuelve los fallos con un 500, pero con el detalle en el cuerpo: se lee el cuerpo sea cual sea el código.
        return try {
            when (val answer = QuotaSoap.parse(body)) {
                is QuotaSoap.Answer.Quota -> QuotaFetch.Success(answer.assignedMb, answer.usedMb, answer.level)
                is QuotaSoap.Answer.Fault -> QuotaFetch.WrongCredentials(answer.message.ifBlank { answer.code })
            }
        } catch (e: IllegalArgumentException) {
            QuotaFetch.ServiceChanged(e.message.orEmpty())
        }
    }
}

/** Lo común a todos los motores: un tope de tiempo, para no esperar indefinidamente fuera de la red de la UCI. */
internal fun io.ktor.client.HttpClientConfig<*>.quotaDefaults() {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    expectSuccess = false
}
