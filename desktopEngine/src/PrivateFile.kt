package org.uci.proxy.desktop

import org.uci.proxy.core.platform.windows.WindowsAccounts
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/** Archivos que solo debe poder leer quien los crea, como el que lleva el token entre la ventana y el motor. */
object PrivateFile {
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    /**
     * Crea un archivo temporal legible solo por su dueño.
     *
     * En sistemas POSIX son los permisos `rw-------`. En Windows (NTFS no tiene permisos POSIX) se sustituye
     * la lista de acceso heredada por una propia: el dueño y el grupo de Administradores. Este último hace
     * falta porque, si el usuario no es administrador, el aviso de UAC pide las credenciales de otro
     * administrador y el motor corre con esa cuenta, que entonces no sería el dueño.
     */
    fun createTemp(prefix: String, suffix: String): Path {
        if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix")) {
            return Files.createTempFile(prefix, suffix, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        }
        val file = Files.createTempFile(prefix, suffix)
        try {
            restrictWithAcl(file)
        } catch (e: IOException) {
            Files.deleteIfExists(file)
            throw e
        }
        return file
    }

    private fun restrictWithAcl(file: Path) {
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
            ?: throw IOException("El sistema de archivos no permite restringir el acceso a $file")
        val principals = buildList {
            add(view.owner)
            administrators(file)?.let(::add)
        }
        // Reemplaza por completo la lista heredada de la carpeta temporal, que puede incluir a otros usuarios.
        view.acl = principals.map { principal ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.values().toSet())
                .build()
        }
    }

    // Por su SID: el nombre depende del idioma de Windows. Sin él queda el dueño solo.
    private fun administrators(file: Path): UserPrincipal? = WindowsAccounts.administrators(file.fileSystem.userPrincipalLookupService)
}
