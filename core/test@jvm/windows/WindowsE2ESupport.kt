package org.uci.proxy.core.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.uci.proxy.core.platform.ProcessCommandRunner
import org.uci.proxy.core.platform.windows.WinPlatformTunnel
import org.uci.proxy.core.port.LogLevel
import org.uci.proxy.core.port.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Lo que comparten las pruebas del túnel de Windows con el adaptador real. Solo corren en el trabajo de CI de
 * Windows, que arranca fuera del código:
 * - `tools/test_proxy.py --auth ntlm --upstream --redirect 127.0.0.1:<origen>` en el puerto del proxy, y
 * - un servidor HTTP en el puerto de origen que sirve `big.bin` (5 MB), cuya huella va en [BIG_SHA].
 *
 * Las rutas que entran en el túnel se limitan a 192.0.2.0/24 (TEST-NET-1, reservada para documentación): el resto
 * del tráfico del ejecutor, incluida su conexión con GitHub, no se toca.
 */
internal object WindowsE2E {
    const val PROXY_PORT_VAR = "PROXYUCI_E2E_PROXY_PORT"
    const val ORIGIN_PORT_VAR = "PROXYUCI_E2E_ORIGIN_PORT"
    const val BIG_SHA_VAR = "PROXYUCI_E2E_BIG_SHA"
    const val BIG_SIZE = 5 * 1024 * 1024

    val proxyPort: Int get() = System.getenv(PROXY_PORT_VAR)?.toInt() ?: 3128
    val originPort: Int get() = System.getenv(ORIGIN_PORT_VAR)?.toInt() ?: 8000
    val expectedSha: String get() = requireNotNull(System.getenv(BIG_SHA_VAR)) { "Falta $BIG_SHA_VAR" }

    class PrintLogger : Logger {
        override fun log(level: LogLevel, tag: String, message: String) = println("APP $level $tag: $message")
    }

    fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Resuelve un nombre inventado con el DNS del sistema, reintentando unos segundos: Windows tarda en empezar a
     * usar el servidor DNS de un adaptador recién configurado. Java recuerda 10 s los nombres que no resolvieron,
     * así que cada intento usa un nombre nuevo. Si no resuelve, vuelca el estado de la red **con el túnel activo**
     * y consulta al DNS del túnel directamente, para distinguir «el túnel no responde» de «Windows no lo consulta».
     */
    fun resolveOrDiagnose(): Pair<String, InetAddress> {
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        var last: Exception? = null
        repeat(15) { attempt ->
            val name = "e2e-${System.nanoTime()}.uci.test"
            try {
                val address = InetAddress.getByName(name)
                println("E2E-WINDOWS: $name resolvió a ${address.hostAddress} en el intento ${attempt + 1}")
                return name to address
            } catch (e: UnknownHostException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        val runner = ProcessCommandRunner()
        fun show(title: String, vararg command: String) =
            println("E2E-DIAG [$title]\n" + runner.run(command.toList(), null).output.trim())
        show("rutas", "route", "print", "-4")
        show("DNS por interfaz", "netsh", "interface", "ipv4", "show", "dnsservers")
        show("configuración", "ipconfig", "/all")
        throw AssertionError("Windows no resolvió un nombre por el DNS del túnel: $last")
    }

    fun download(url: String): ByteArray {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        return connection.getInputStream().use { it.readBytes() }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** El nombre de la interfaz por la que Windows mandaría el tráfico hacia [address]. */
    fun routeInterface(address: String): String = ProcessCommandRunner().run(
        listOf("powershell", "-NoProfile", "-Command", "(Find-NetRoute -RemoteIPAddress $address | Select-Object -First 1).InterfaceAlias"),
        null,
    ).output.trim()

    /** Windows tarda un instante en retirar el adaptador: se espera un poco antes de darlo por presente. */
    fun adapterExists(): Boolean = runBlocking {
        withContext(Dispatchers.IO) {
            repeat(20) {
                if (!listing().contains(WinPlatformTunnel.ADAPTER_NAME)) return@withContext false
                delay(500)
            }
            true
        }
    }

    private fun listing(): String =
        ProcessCommandRunner().run(listOf("netsh", "interface", "ipv4", "show", "interfaces"), null).output
}
