package org.uci.proxy.quota

import org.uci.proxy.core.port.QuotaFetch
import org.uci.proxy.core.quota.QuotaSoap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El cliente de la app contra el servicio de cuotas de verdad. Solo desde la red de la UCI y con una cuenta:
 *
 *     PROXYUCI_QUOTA_USER=usuario PROXYUCI_QUOTA_PASSWORD='clave' ./kotlin test -m shared -p jvm
 *
 * Sin esas variables (el CI) no hace nada.
 */
class UciQuotaLiveTest {
    @Test
    fun `the real service gives the quota of the account`() {
        val user = System.getenv("PROXYUCI_QUOTA_USER") ?: return
        val password = System.getenv("PROXYUCI_QUOTA_PASSWORD") ?: return

        val result = runBlocking { HttpUciQuotaService().fetch(user, password, QuotaSoap.SERVICE_URL, verifyCertificate = false) }

        println("Cuota de $user: $result")
        val quota = assertIs<QuotaFetch.Success>(result)
        assertTrue(quota.assignedMb > 0 && quota.usedMb >= 0 && quota.level.isNotBlank())
    }

    @Test
    fun `the real service rejects a wrong password`() {
        System.getenv("PROXYUCI_QUOTA_USER") ?: return

        val result = runBlocking {
            HttpUciQuotaService().fetch("proxyuci.prueba.inexistente", "x", QuotaSoap.SERVICE_URL, verifyCertificate = false)
        }

        assertIs<QuotaFetch.WrongCredentials>(result)
    }

    @Test
    fun `verifying the certificate fails because the uci authority is not in the system`() {
        System.getenv("PROXYUCI_QUOTA_USER") ?: return

        val result = runBlocking { HttpUciQuotaService().fetch("x", "x", QuotaSoap.SERVICE_URL, verifyCertificate = true) }

        assertIs<QuotaFetch.UntrustedCertificate>(result)
    }
}
