package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.uci.proxy.core.port.NetFailure
import org.uci.proxy.core.port.QuotaFetch
import org.uci.proxy.core.quota.QuotaSoap

/** Respuestas de ejemplo con la forma de un servicio SOAP en PHP, como el de la UCI. */
private fun answer(vararg fields: Pair<String, String>) =
    """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="https://cuotas.uci.cu/">
  <SOAP-ENV:Body>
    <ns1:ObtenerCuotaResponse>
      <ObtenerCuotaResult>${fields.joinToString("") { (name, value) -> "<$name>$value</$name>" }}</ObtenerCuotaResult>
    </ns1:ObtenerCuotaResponse>
  </SOAP-ENV:Body>
</SOAP-ENV:Envelope>"""


/** El fallo del servicio de la UCI con usuario o contraseña malos. */
private const val FAULT = """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body><SOAP-ENV:Fault><faultcode>InetCuotas:Cuotas</faultcode><faultstring>El usuario no es válido o lo contraseña es incorrecta</faultstring></SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>"""

class HttpUciQuotaServiceTest {
    private fun service(status: HttpStatusCode, body: String, seen: MutableList<String> = mutableListOf()) = HttpUciQuotaService { _ ->
        HttpClient(
            MockEngine { request ->
                seen += request.headers["SOAPAction"].orEmpty()
                seen += request.body.toByteArray().decodeToString()
                respond(body, status, headersOf("Content-Type", "text/xml"))
            },
        ) { quotaDefaults() }
    }

    @Test
    fun `a good answer is the quota`() = runTest {
        val seen = mutableListOf<String>()
        val result = service(HttpStatusCode.OK, answer("a" to "2000", "b" to "10.5", "c" to "Estudiantes"), seen)
            .fetch("ana", "clave", QuotaSoap.SERVICE_URL, verifyCertificate = true)

        assertEquals(QuotaFetch.Success(2000, 10.5, "Estudiantes"), result)
        assertEquals("\"${QuotaSoap.SOAP_ACTION}\"", seen[0])
        assertTrue("<usuario>ana</usuario>" in seen[1])
    }

    @Test
    fun `a fault with a 500 is wrong credentials`() = runTest {
        val result = service(HttpStatusCode.InternalServerError, FAULT).fetch("ana", "mala", QuotaSoap.SERVICE_URL, true)

        assertEquals(QuotaFetch.WrongCredentials("El usuario no es válido o lo contraseña es incorrecta"), result)
    }

    @Test
    fun `a page that is not soap means the service changed`() = runTest {
        assertIs<QuotaFetch.ServiceChanged>(service(HttpStatusCode.OK, "<html>hola</html>").fetch("ana", "x", QuotaSoap.SERVICE_URL, true))
    }

    @Test
    fun `a network failure means unreachable`() = runTest {
        val failing = HttpUciQuotaService { _ -> HttpClient(MockEngine { throw RuntimeException("sin red") }) { quotaDefaults() } }

        assertEquals(QuotaFetch.Unreachable(NetFailure.OTHER, "sin red"), failing.fetch("ana", "x", QuotaSoap.SERVICE_URL, true))
    }
}
