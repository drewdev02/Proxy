package org.uci.proxy.core.proxy

import org.uci.proxy.core.model.AppSettings
import org.uci.proxy.core.model.IpNameService
import org.uci.proxy.core.model.NameOutcome
import org.uci.proxy.core.port.NameServiceReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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

    private class Reports : NameServiceReporter {
        val all = mutableListOf<Pair<String, NameOutcome>>()

        override fun report(serviceId: String, outcome: NameOutcome) {
            all += serviceId to outcome
        }
    }

    private val time = TestTimeSource()

    private fun namer(reports: Reports = Reports(), settings: AppSettings = AppSettings()) = IpNamer(settings, reports, time)

    private fun IpNamer.refuseFirst(times: Int) = repeat(times) { refused(attempts(publicIp).first()) }

    @Test
    fun `a service the proxy keeps refusing is set aside`() {
        val namer = namer()

        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN)

        assertEquals(listOf("149.22.82.28.nip.io", publicIp), hosts(namer), "sslip.io queda apartado")
    }

    @Test
    fun `fewer refusals than the limit do not set it aside`() {
        val namer = namer()

        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN - 1)

        assertEquals("149-22-82-28.sslip.io", hosts(namer).first())
    }

    @Test
    fun `a success in between resets the count`() {
        val namer = namer()
        namer.refuseFirst(2)
        namer.succeeded(namer.attempts(publicIp).first())

        namer.refuseFirst(2)

        assertEquals("149-22-82-28.sslip.io", hosts(namer).first(), "las dos primeras no cuentan: hubo un acierto")
    }

    @Test
    fun `a set aside service comes back after the cooldown with a clean slate`() {
        val namer = namer()
        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN)

        time += 5.minutes + 1.seconds

        assertEquals("149-22-82-28.sslip.io", hosts(namer).first())
        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN - 1)
        assertEquals("149-22-82-28.sslip.io", hosts(namer).first(), "vuelve con la cuenta a cero")
    }

    @Test
    fun `it stays set aside until the cooldown ends`() {
        val namer = namer()
        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN)

        time += 4.minutes

        assertEquals("149.22.82.28.nip.io", hosts(namer).first())
    }

    @Test
    fun `when every active service is set aside they are all tried anyway`() {
        val namer = namer()
        repeat(IpNamer.FAILURES_BEFORE_COOLDOWN) {
            namer.attempts(publicIp).filter { it.service != null }.forEach(namer::refused)
        }

        assertEquals(listOf("149-22-82-28.sslip.io", "149.22.82.28.nip.io", publicIp), hosts(namer))
    }

    @Test
    fun `a success brings a set aside service back at once`() {
        val namer = namer()
        val sslip = namer.attempts(publicIp).first()
        namer.refuseFirst(IpNamer.FAILURES_BEFORE_COOLDOWN)

        namer.succeeded(sslip)

        assertEquals("149-22-82-28.sslip.io", hosts(namer).first())
    }

    @Test
    fun `refusals and successes are reported by service and never for the bare address`() {
        val reports = Reports()
        val namer = namer(reports)
        val attempts = namer.attempts(publicIp)

        namer.refused(attempts[0])
        namer.succeeded(attempts[1])
        namer.refused(attempts[2]) // la IP tal cual
        namer.succeeded(attempts[2])

        assertEquals(listOf("sslip" to NameOutcome.REFUSED, "nip" to NameOutcome.WORKED), reports.all)
    }
}
