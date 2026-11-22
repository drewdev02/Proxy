package org.uci.proxy.quota

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * El cliente de verdad (OkHttp) contra el simulador del servicio de cuotas (tools/test_cuotas.py), por HTTP y por HTTPS
 * con certificado propio. Sin Python en el equipo, no se prueba (el CI lo tiene en todos los sistemas).
 */
class QuotaSimulatorTest {
    private val processes = mutableListOf<Process>()

    @AfterTest
    fun stop() {
        processes.forEach { it.destroy() }
    }

    private fun python(): String? = listOf("python3", "python").firstOrNull { command ->
        runCatching {
            val process = ProcessBuilder(command, "--version").redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    /** Arranca el simulador y devuelve su dirección, o null si no se pudo (sin Python, o sin openssl para --tls). */
    private fun simulator(vararg args: String): String? {
        val python = python() ?: return null.also { println("Sin Python: no se prueba contra el simulador de cuotas") }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "tools/test_cuotas.py") }.firstOrNull { it.exists() }
            ?: error("No se encontró tools/test_cuotas.py")
        val process = ProcessBuilder(python, "-u", script.path, "serve", "--port", "0", *args).redirectErrorStream(true).start()
        processes += process
        val reader = process.inputStream.bufferedReader()
        val line = generateSequence { reader.readLine() }.firstOrNull { "Servicio de cuotas de pruebas en" in it || "necesita openssl" in it }
            ?: error("El simulador de cuotas no arrancó")
        if ("necesita openssl" in line) return null.also { println("Sin openssl: no se prueba el simulador por HTTPS") }
        Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
        return Regex("(https?://\\S+)").find(line)?.groupValues?.get(1)
    }

    @Test
    fun `the client gets the quota from the simulator over http`(): Unit = runBlocking {
        val url = simulator("--quota", "2000", "--used", "1420.53", "--level", "Profesores", "--field-names", "x,y,z") ?: return@runBlocking
        val service = HttpUciQuotaService()

        assertEquals(QuotaFetch.Success(2000, 1420.53, "Profesores"), service.fetch("andrey", "secreta7", url, verifyCertificate = true))
        assertIs<QuotaFetch.WrongCredentials>(service.fetch("andrey", "otra", url, verifyCertificate = true))
    }

    @Test
    fun `over https its own certificate is rejected unless verification is off`(): Unit = runBlocking {
        val url = simulator("--tls") ?: return@runBlocking
        val service = HttpUciQuotaService()

        assertIs<QuotaFetch.UntrustedCertificate>(service.fetch("andrey", "secreta7", url, verifyCertificate = true))
        assertIs<QuotaFetch.Success>(service.fetch("andrey", "secreta7", url, verifyCertificate = false))
    }

    @Test
    fun `nobody listening is unreachable`() {
        val result = runBlocking { HttpUciQuotaService().fetch("andrey", "x", "http://127.0.0.1:9/servicios/v1/InetCuotasWS.php", verifyCertificate = true) }

        assertIs<QuotaFetch.Unreachable>(result)
    }
}
