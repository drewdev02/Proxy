package org.uci.proxy.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.uci.proxy.core.port.NetFailure

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

/** Tal cual la da el servicio de la UCI (con otro usuario y otras cifras). */
private const val REAL = """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="urn:InetCuotasWS" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:SOAP-ENC="http://schemas.xmlsoap.org/soap/encoding/" SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><SOAP-ENV:Body><ns1:ObtenerCuotaResponse><ObtenerCuotaReturn xsi:type="ns1:Usuario"><cuota xsi:type="xsd:float">240</cuota><cuota_usada xsi:type="xsd:float">171.97552394867</cuota_usada><nivel_navegacion xsi:type="xsd:string">Navegación plena</nivel_navegacion><usuario xsi:type="xsd:string">ana.perez</usuario></ObtenerCuotaReturn></ns1:ObtenerCuotaResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>"""

/** El fallo del servicio de la UCI con usuario o contraseña malos. */
private const val FAULT = """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body><SOAP-ENV:Fault><faultcode>InetCuotas:Cuotas</faultcode><faultstring>El usuario no es válido o lo contraseña es incorrecta</faultstring></SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>"""

class QuotaSoapTest {
    @Test
    fun `the answer of the real service is understood`() {
        assertEquals(QuotaSoap.Answer.Quota(240, 171.97552394867, "Navegación plena"), QuotaSoap.parse(REAL))
    }

    @Test
    fun `the fields are read by name in any order`() {
        val shuffled = answer("usuario" to "ana", "nivel_navegacion" to "Restringida", "cuota_usada" to "12.5", "cuota" to "500")

        assertEquals(QuotaSoap.Answer.Quota(500, 12.5, "Restringida"), QuotaSoap.parse(shuffled))
    }

    @Test
    fun `with unknown field names the answer is read by position`() {
        val expected = QuotaSoap.Answer.Quota(2000, 1420.53, "Profesores")

        assertEquals(expected, QuotaSoap.parse(answer("cuota" to "2000", "usada" to "1420.53", "nivel" to "Profesores")))
        assertEquals(expected, QuotaSoap.parse(answer("a" to "2000", "b" to "1420.53", "c" to "Profesores")))
    }

    @Test
    fun `numbers with a decimal comma and text with entities are understood`() {
        val parsed = QuotaSoap.parse(answer("x" to " 2000 ", "y" to "1420,5", "z" to "Investigaci&#243;n &amp; Postgrado"))

        assertEquals(QuotaSoap.Answer.Quota(2000, 1420.5, "Investigación & Postgrado"), parsed)
    }

    @Test
    fun `a soap fault means wrong credentials`() {
        assertEquals(QuotaSoap.Answer.Fault("InetCuotas:Cuotas", "El usuario no es válido o lo contraseña es incorrecta"), QuotaSoap.parse(FAULT))
    }

    @Test
    fun `an answer that is not understood is rejected`() {
        assertFailsWith<IllegalArgumentException> { QuotaSoap.parse("<html>Mantenimiento</html>") }
        assertFailsWith<IllegalArgumentException> { QuotaSoap.parse(answer("cuota" to "mucha", "usada" to "1", "nivel" to "x")) }
        assertFailsWith<IllegalArgumentException> { QuotaSoap.parse(answer("cuota" to "2000")) }
        assertFailsWith<IllegalArgumentException> { QuotaSoap.parse("<a><b></a>") }
    }

    @Test
    fun `the request carries the account the password escaped and the domain`() {
        val request = QuotaSoap.request("ana", "a<b&c\"")

        assertTrue("<usuario>ana</usuario>" in request)
        assertTrue("<clave>a&lt;b&amp;c&quot;</clave>" in request)
        assertTrue("<dominio>uci.cu</dominio>" in request)
        assertTrue("<ObtenerCuota xmlns=\"urn:InetCuotasWS\">" in request)
    }
}

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
