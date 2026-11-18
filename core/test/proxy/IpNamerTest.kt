package org.uci.proxy.core.proxy

import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.settings.IpNameService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IpNamerTest {
    private val publicIp = "149.22.82.28"
    private fun hosts(namer: IpNamer, host: String = publicIp) = namer.attempts(host).map { it.host }

    @Test
    fun `a public IP is tried by each active service in order and then by address`() {
        val namer = IpNamer(AppSettings())

        assertEquals(listOf("149-22-82-28.sslip.io", "149.22.82.28.nip.io", publicIp), hosts(namer))
    }

    @Test
    fun `templates put the IP with dots or with dashes`() {
        assertEquals("149-22-82-28.sslip.io", IpNameService.SSLIP.nameFor(publicIp))
        assertEquals("149.22.82.28.nip.io", IpNameService.NIP.nameFor(publicIp))
        assertEquals("ip-149-22-82-28.mio.test", IpNameService("x", "mío", "ip-{ip-}.mio.test").nameFor(publicIp))
    }

    @Test
    fun `disabled services are skipped`() {
        val namer = IpNamer(AppSettings(nameServices = listOf(IpNameService.SSLIP.copy(enabled = false), IpNameService.NIP)))

        assertEquals(listOf("149.22.82.28.nip.io", publicIp), hosts(namer))
    }

    @Test
    fun `the order of the list is the order of the attempts`() {
        val namer = IpNamer(AppSettings(nameServices = listOf(IpNameService.NIP, IpNameService.SSLIP)))

        assertEquals(listOf("149.22.82.28.nip.io", "149-22-82-28.sslip.io", publicIp), hosts(namer))
    }

    @Test
    fun `the address is the last resort and can be turned off`() {
        val namer = IpNamer(AppSettings(tryRawIp = false))

        assertEquals(listOf("149-22-82-28.sslip.io", "149.22.82.28.nip.io"), hosts(namer))
    }

    @Test
    fun `with no active service the address is still tried even if that was turned off`() {
        val none = AppSettings(tryRawIp = false, nameServices = IpNameService.BUILT_IN.map { it.copy(enabled = false) })

        assertEquals(listOf(publicIp), hosts(IpNamer(none)))
    }

    @Test
    fun `with names turned off a public IP goes as it is`() {
        assertEquals(listOf(publicIp), hosts(IpNamer(AppSettings(nameByIp = false))))
    }

    @Test
    fun `the service that worked last goes first next time`() {
        val namer = IpNamer(AppSettings())
        val second = namer.attempts(publicIp)[1]

        namer.succeeded(second)

        assertEquals(listOf("149.22.82.28.nip.io", "149-22-82-28.sslip.io", publicIp), hosts(namer))
        assertEquals(listOf("8.8.8.8.nip.io", "8-8-8-8.sslip.io", "8.8.8.8"), hosts(namer, "8.8.8.8"), "vale para cualquier IP")
    }

    @Test
    fun `succeeding by address does not change the order`() {
        val namer = IpNamer(AppSettings())

        namer.succeeded(namer.attempts(publicIp).last())

        assertEquals(listOf("149-22-82-28.sslip.io", "149.22.82.28.nip.io", publicIp), hosts(namer))
    }

    @Test
    fun `names and private IPs are asked for as they are`() {
        val namer = IpNamer(AppSettings())

        listOf("example.com", "149-22-82-28.sslip.io", "10.0.0.1", "10.98.0.1", "172.16.5.4", "192.168.1.10", "127.0.0.1", "100.64.0.1").forEach {
            assertEquals(listOf(it), hosts(namer, it), it)
        }
    }

    @Test
    fun `things that are not an IP are asked for as they are`() {
        val namer = IpNamer(AppSettings())

        listOf("149.22.82", "149.22.82.28.5", "999.1.1.1", "", "::1").forEach { assertEquals(listOf(it), hosts(namer, it), it) }
    }

    @Test
    fun `attempts by address carry no service`() {
        assertNull(IpNamer(AppSettings()).attempts(publicIp).last().service)
    }
}
