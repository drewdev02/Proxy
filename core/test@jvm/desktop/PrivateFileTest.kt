package org.uci.proxy.core.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprueba que solo el dueño (y, en Windows, el grupo de Administradores) puede acceder a [file].
 * Se ejecuta con el sistema de archivos real, así que en CI lo verifica cada sistema operativo.
 */
internal fun assertOnlyOwnerCanAccess(file: Path) {
    val posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView::class.java)
    if (posix != null) {
        assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()))
        return
    }
    val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
    val owner = acl.owner
    val entries = acl.acl
    assertTrue(entries.isNotEmpty(), "la lista de acceso no puede estar vacía")
    assertTrue(entries.all { it.type() == AclEntryType.ALLOW })
    // Nadie más que el dueño y los administradores: ni «Usuarios» ni «Todos».
    val administrators = WindowsAccounts.administrators(file.fileSystem.userPrincipalLookupService)
    val others = entries.map { it.principal() }.filter { it != owner }
    assertTrue(others.all { it == administrators }, "acceso para: $others")
}

class PrivateFileTest {
    @Test
    fun `on Windows the administrators group can open the file, so an engine run by another administrator reads the token`() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val file = PrivateFile.createTemp("proxyuci-test-", ".token")
        try {
            val administrators = WindowsAccounts.administrators(file.fileSystem.userPrincipalLookupService)
            val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl

            assertTrue(acl.any { it.principal() == administrators }, "sin entrada para Administradores: $acl")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a private temp file can be written and read by its owner and nobody else`() {
        val file = PrivateFile.createTemp("proxyuci-test-", ".token")
        try {
            Files.writeString(file, "secreto")

            assertEquals("secreto", Files.readString(file))
            assertOnlyOwnerCanAccess(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
