package org.uci.proxy.core.settings

import org.uci.proxy.core.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredSettingsRepositoryTest {
    private val store = InMemoryKeyValueStore()

    @Test
    fun `answering connectivity checks is on by default`() {
        assertTrue(AppSettings().answerConnectivityChecks)
        assertTrue(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)
    }

    @Test
    fun `a change is published and survives a restart`() {
        val repository = StoredSettingsRepository(store)

        repository.setAnswerConnectivityChecks(false)

        assertFalse(repository.settings.value.answerConnectivityChecks)
        assertFalse(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)

        repository.setAnswerConnectivityChecks(true)
        assertTrue(StoredSettingsRepository(store).settings.value.answerConnectivityChecks)
    }

    @Test
    fun `a stored value that makes no sense falls back to the default`() {
        store.putString(StoredSettingsRepository.ANSWER_CONNECTIVITY_CHECKS_KEY, "quizás")

        assertEquals(AppSettings(), StoredSettingsRepository(store).settings.value)
    }

    private fun reopened() = StoredSettingsRepository(store)

    @Test
    fun `the quota settings start with every alert and survive a restart`() {
        val repository = StoredSettingsRepository(store)
        assertEquals(QuotaSettings(alerts = listOf(50, 75, 90), checkMinutes = 5, pauseAtLimit = false, verifyCertificate = false), repository.settings.value.quota)

        repository.updateQuota { it.copy(alerts = listOf(90), checkMinutes = 15, pauseAtLimit = true) }

        assertEquals(QuotaSettings(alerts = listOf(90), checkMinutes = 15, pauseAtLimit = true), reopened().settings.value.quota)
    }

    @Test
    fun `broken quota settings fall back to the defaults`() {
        store.putString(StoredSettingsRepository.QUOTA_KEY, "{no es json")

        assertEquals(QuotaSettings(), reopened().settings.value.quota)
    }

    @Test
    fun `opening the local network without the proxy is on by default and survives a restart`() {
        assertTrue(AppSettings().intranetDirect)
        assertTrue(reopened().settings.value.intranetDirect, "también para quien ya tenía ajustes guardados")

        val repository = reopened()
        repository.setIntranetDirect(false)

        assertFalse(repository.settings.value.intranetDirect)
        assertFalse(reopened().settings.value.intranetDirect)
    }

    @Test
    fun `falling back to the proxy when Otro País is down is on by default and survives a restart`() {
        assertTrue(AppSettings().vpnFallbackToProxy)
        store.putString(StoredSettingsRepository.INTRANET_DIRECT_KEY, "false")
        assertTrue(reopened().settings.value.vpnFallbackToProxy, "también para quien ya tenía ajustes guardados sin la clave")

        val repository = reopened()
        repository.setVpnFallbackToProxy(false)

        assertFalse(repository.settings.value.vpnFallbackToProxy)
        assertFalse(reopened().settings.value.vpnFallbackToProxy)
    }

    @Test
    fun `names for public IPs are on by default with sslip and nip in that order`() {
        val settings = reopened().settings.value

        assertTrue(settings.nameByIp)
        assertTrue(settings.tryRawIp)
        assertEquals(listOf("sslip", "nip"), settings.nameServices.map { it.id })
        assertTrue(settings.nameServices.all { it.enabled && it.builtIn })
    }

    @Test
    fun `the two switches are published and survive a restart`() {
        val repository = reopened()

        repository.setNameByIp(false)
        repository.setTryRawIp(false)

        assertFalse(reopened().settings.value.nameByIp)
        assertFalse(reopened().settings.value.tryRawIp)
        assertFalse(repository.settings.value.nameByIp)
    }

    @Test
    fun `a service can be switched off and its choice survives a restart`() {
        reopened().setNameServiceEnabled("sslip", false)

        val services = reopened().settings.value.nameServices
        assertFalse(services.single { it.id == "sslip" }.enabled)
        assertTrue(services.single { it.id == "nip" }.enabled)
    }

    @Test
    fun `a service moves up and down the list but not past the ends`() {
        val repository = reopened()

        repository.moveNameService("nip", -1)
        assertEquals(listOf("nip", "sslip"), repository.settings.value.nameServices.map { it.id })
        assertEquals(listOf("nip", "sslip"), reopened().settings.value.nameServices.map { it.id })

        repository.moveNameService("nip", -1)
        assertEquals(listOf("nip", "sslip"), repository.settings.value.nameServices.map { it.id }, "ya está arriba")

        repository.moveNameService("nip", 1)
        repository.moveNameService("nip", 1)
        assertEquals(listOf("sslip", "nip"), repository.settings.value.nameServices.map { it.id })
    }

    @Test
    fun `moving or switching a service that does not exist changes nothing`() {
        val repository = reopened()

        repository.moveNameService("no-existe", 1)
        repository.setNameServiceEnabled("no-existe", false)

        assertEquals(IpNameService.BUILT_IN, repository.settings.value.nameServices)
    }

    @Test
    fun `restoring puts the services back as they come from the factory`() {
        val repository = reopened()
        repository.moveNameService("nip", -1)
        repository.setNameServiceEnabled("sslip", false)

        repository.resetNameServices()

        assertEquals(IpNameService.BUILT_IN, repository.settings.value.nameServices)
        assertEquals(IpNameService.BUILT_IN, reopened().settings.value.nameServices)
    }

    @Test
    fun `a stored list that makes no sense falls back to the factory services`() {
        store.putString(StoredSettingsRepository.NAME_SERVICES_KEY, "esto no es json")

        assertEquals(IpNameService.BUILT_IN, reopened().settings.value.nameServices)
    }

    @Test
    fun `a factory service that a stored list lacks is added at the end`() {
        store.putString(StoredSettingsRepository.NAME_SERVICES_KEY, """[{"id":"nip","name":"nip.io","template":"{ip}.nip.io","builtIn":true}]""")

        assertEquals(listOf("nip", "sslip"), reopened().settings.value.nameServices.map { it.id })
    }

    private val custom = IpNameService(id = "custom-1", name = "Mío", template = "{ip-}.mio.com")

    @Test
    fun `a custom service is added at the end and active and survives a restart`() {
        reopened().addNameService(custom)

        val services = reopened().settings.value.nameServices
        assertEquals(listOf("sslip", "nip", "custom-1"), services.map { it.id })
        assertTrue(services.last().enabled)
        assertFalse(services.last().builtIn)
    }

    @Test
    fun `a service added by hand is never marked as factory made`() {
        reopened().addNameService(custom.copy(builtIn = true))

        assertFalse(reopened().settings.value.nameServices.last().builtIn)
    }

    @Test
    fun `adding the same id twice keeps one`() {
        val repository = reopened()
        repository.addNameService(custom)
        repository.addNameService(custom.copy(name = "Otro"))

        assertEquals(1, repository.settings.value.nameServices.count { it.id == "custom-1" })
    }

    @Test
    fun `a custom service can be removed but a factory one cannot`() {
        val repository = reopened()
        repository.addNameService(custom)

        repository.removeNameService("custom-1")
        repository.removeNameService("sslip")

        assertEquals(listOf("sslip", "nip"), repository.settings.value.nameServices.map { it.id })
        assertEquals(listOf("sslip", "nip"), reopened().settings.value.nameServices.map { it.id })
    }

    @Test
    fun `restoring also deletes the custom services`() {
        val repository = reopened()
        repository.addNameService(custom)

        repository.resetNameServices()

        assertEquals(IpNameService.BUILT_IN, repository.settings.value.nameServices)
    }
}
