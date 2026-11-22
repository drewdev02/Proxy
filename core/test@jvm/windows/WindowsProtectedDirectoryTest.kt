package org.uci.proxy.core.windows

import org.uci.proxy.core.platform.windows.WindowsAccounts
import org.uci.proxy.core.platform.windows.WindowsProtectedDirectory
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La carpeta de la que el motor elevado carga las DLL, con el sistema de archivos real. Solo tiene sentido en Windows (en
 * el CI, donde las pruebas corren como Administrador); en los demás sistemas se salta.
 */
class WindowsProtectedDirectoryTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun base(): Path = createTempDirectory("proxyuci-protegida")

    /** Dueño Administradores y solo SYSTEM y Administradores en la lista de acceso. */
    private fun assertOnlyAdministrators(directory: Path) {
        val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
        val lookup = directory.fileSystem.userPrincipalLookupService
        val administrators = WindowsAccounts.administrators(lookup)
        val allowed = setOf(administrators, WindowsAccounts.system(lookup))
        assertEquals(administrators, view.owner, "dueño de $directory")
        val acl = view.acl
        assertTrue(acl.isNotEmpty())
        assertTrue(acl.all { it.type() == AclEntryType.ALLOW && it.principal() in allowed }, "acceso a $directory: $acl")
    }

    @Test
    fun `a new directory and its parent end up owned by administrators and closed to everyone else`() {
        if (!isWindows) return
        val native = base().resolve("ProxyUCI").resolve("native")

        assertEquals(native, WindowsProtectedDirectory.prepare(native))

        assertOnlyAdministrators(native.parent)
        assertOnlyAdministrators(native)
        assertTrue(WindowsProtectedDirectory.isProtected(native.parent))
        assertTrue(WindowsProtectedDirectory.isProtected(native))
        // Lo que el motor crea dentro (la carpeta de cada DLL) hereda la misma lista.
        assertOnlyAdministrators(Files.createTempDirectory(native, "wintun-"))
    }

    @Test
    fun `a directory with the inherited permissions of its parent is not protected`() {
        if (!isWindows) return
        val plain = Files.createDirectory(base().resolve("ProxyUCI"))

        assertFalse(WindowsProtectedDirectory.isProtected(plain))
    }

    @Test
    fun `a directory created earlier by someone else is set aside and created again`() {
        if (!isWindows) return
        val app = Files.createDirectory(base().resolve("ProxyUCI"))
        val planted = Files.createDirectories(app.resolve("native")).resolve("wintun.dll")
        Files.writeString(planted, "DLL ajena")

        val native = WindowsProtectedDirectory.prepare(app.resolve("native"))

        assertTrue(WindowsProtectedDirectory.isProtected(app))
        assertTrue(WindowsProtectedDirectory.isProtected(native))
        assertFalse(Files.exists(planted), "lo que dejó otro no sigue en el camino")
    }

    @Test
    fun `calling prepare again keeps a directory that is already protected`() {
        if (!isWindows) return
        val native = base().resolve("ProxyUCI").resolve("native")
        WindowsProtectedDirectory.prepare(native)
        val kept = Files.writeString(native.resolve("marca"), "sigue")

        WindowsProtectedDirectory.prepare(native)

        assertTrue(Files.exists(kept))
    }

    @Test
    fun `a junction in place of the directory is replaced without touching where it pointed`() {
        if (!isWindows) return
        val root = base()
        val elsewhere = Files.createDirectory(root.resolve("otra"))
        val bystander = Files.writeString(elsewhere.resolve("no-borrar"), "de otro")
        val app = root.resolve("ProxyUCI")
        val mklink = ProcessBuilder("cmd", "/c", "mklink", "/J", app.toString(), elsewhere.toString()).redirectErrorStream(true).start()
        assertEquals(0, mklink.waitFor(), mklink.inputStream.bufferedReader().readText())

        assertFalse(WindowsProtectedDirectory.isProtected(app))
        WindowsProtectedDirectory.prepare(app.resolve("native"))

        assertTrue(WindowsProtectedDirectory.isProtected(app))
        assertTrue(Files.exists(bystander), "apartar la unión no borra lo que hay al otro lado")
    }
}
