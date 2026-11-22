package org.uci.proxy.core.desktop

import com.sun.jna.platform.win32.Advapi32Util
import java.nio.file.FileSystems
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.attribute.UserPrincipalNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Busca solo los nombres que conoce, como un Windows en español. */
private class SpanishLookup : UserPrincipalLookupService() {
    val asked = mutableListOf<String>()
    private val known = setOf("BUILTIN\\Administradores", "NT AUTHORITY\\SYSTEM")

    private fun find(name: String): GroupPrincipal {
        asked += name
        if (name !in known) throw UserPrincipalNotFoundException(name)
        return object : GroupPrincipal {
            override fun getName() = name
        }
    }

    override fun lookupPrincipalByName(name: String): UserPrincipal = find(name)

    override fun lookupPrincipalByGroupName(group: String): GroupPrincipal = find(group)
}

class WindowsAccountsTest {
    private val spanishNames = mapOf(
        WindowsAccounts.ADMINISTRATORS_SID to "BUILTIN\\Administradores",
        WindowsAccounts.SYSTEM_SID to "NT AUTHORITY\\SYSTEM",
    )

    @Test
    fun `the administrators group is found by its SID whatever the language`() {
        val lookup = SpanishLookup()

        val group = WindowsAccounts.administrators(lookup, nameOf = spanishNames::get)

        assertEquals("BUILTIN\\Administradores", group?.name)
        assertEquals(listOf("BUILTIN\\Administradores"), lookup.asked, "nunca se busca «Administrators» a ciegas")
    }

    @Test
    fun `system is found by its SID too`() {
        assertEquals("NT AUTHORITY\\SYSTEM", WindowsAccounts.system(SpanishLookup(), nameOf = spanishNames::get)?.name)
    }

    @Test
    fun `without a name for the SID there is no group and nothing is looked up`() {
        val lookup = SpanishLookup()

        assertNull(WindowsAccounts.administrators(lookup, nameOf = { null }))
        assertEquals(emptyList(), lookup.asked)
    }

    @Test
    fun `a name the system does not know gives no group`() {
        assertNull(WindowsAccounts.administrators(SpanishLookup(), nameOf = { "BUILTIN\\Administrators" }))
    }

    @Test
    fun `on Windows the real administrators group and system account have the well known SIDs`() {
        if (!isWindows) return
        val lookup = FileSystems.getDefault().userPrincipalLookupService

        val administrators = assertNotNull(WindowsAccounts.administrators(lookup))
        val system = assertNotNull(WindowsAccounts.system(lookup))

        assertEquals(WindowsAccounts.ADMINISTRATORS_SID, Advapi32Util.getAccountByName(administrators.name).sidString)
        assertEquals(WindowsAccounts.SYSTEM_SID, Advapi32Util.getAccountByName(system.name).sidString)
    }
}
