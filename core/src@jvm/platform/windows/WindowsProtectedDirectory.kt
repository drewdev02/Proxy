package org.uci.proxy.core.platform.windows

import com.sun.jna.platform.win32.AccCtrl
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.util.UUID

/**
 * Carpetas en las que solo SYSTEM y Administradores pueden escribir, para lo que el motor elevado carga (`wintun.dll`, la
 * librería de Tailscale).
 *
 * Comprobar la huella de una DLL y cargarla después por su ruta no basta si otro puede cambiarla entre medias. En
 * `ProgramData` un usuario normal puede crear carpetas y ser su dueño: si crea antes `ProxyUCI`, controla todo lo que el
 * motor deje dentro. Por eso cada carpeta del camino debe tener como dueño a Administradores y una lista de acceso propia,
 * sin herencia, solo para SYSTEM y Administradores; si no la tiene, se aparta con otro nombre y se crea de nuevo, sin
 * intentar arreglar una que controla otro.
 */
object WindowsProtectedDirectory {
    private const val ATTEMPTS = 3

    /**
     * Deja [directory] y su carpeta madre (la de la app en `ProgramData`) protegidas y devuelve [directory].
     *
     * @throws WindowsNetworkException si no se pueden dejar así (por ejemplo, sin privilegios de Administrador).
     */
    fun prepare(directory: Path): Path {
        val parent = directory.parent ?: throw WindowsNetworkException("$directory no tiene carpeta madre")
        val accounts = accounts(directory)
        secure(parent, accounts)
        secure(directory, accounts)
        return directory
    }

    /** Si [directory] es una carpeta de verdad, de Administradores, y solo SYSTEM y Administradores tienen acceso. */
    fun isProtected(directory: Path): Boolean = isProtected(directory, accounts(directory))

    private class Accounts(val administrators: UserPrincipal, val allowed: Set<UserPrincipal>)

    private fun accounts(directory: Path): Accounts {
        val lookup = directory.fileSystem.userPrincipalLookupService
        val administrators = WindowsAccounts.administrators(lookup)
            ?: throw WindowsNetworkException("No se encontró el grupo de Administradores")
        val system = WindowsAccounts.system(lookup) ?: throw WindowsNetworkException("No se encontró la cuenta SYSTEM")
        return Accounts(administrators, setOf(administrators, system))
    }

    private fun secure(directory: Path, accounts: Accounts) {
        try {
            repeat(ATTEMPTS) {
                if (!Files.exists(directory, NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(directory)
                        restrict(directory, accounts)
                    } catch (_: FileAlreadyExistsException) {
                        // Otro la creó justo ahora: se juzga como cualquier otra.
                    }
                }
                if (isProtected(directory, accounts)) return
                moveAside(directory)
            }
        } catch (e: IOException) {
            throw WindowsNetworkException("No se pudo proteger $directory: ${e.message}")
        }
        throw WindowsNetworkException("No se pudo proteger $directory: otro proceso la sigue creando")
    }

    private fun restrict(directory: Path, accounts: Accounts) {
        val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("el sistema de archivos no tiene listas de acceso")
        view.owner = accounts.administrators
        // Heredable: las carpetas temporales que se creen dentro quedan igual de cerradas.
        view.acl = accounts.allowed.map { principal ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.entries.toSet())
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build()
        }
        protectFromInheritance(directory)
    }

    private fun isProtected(directory: Path, accounts: Accounts): Boolean {
        val attributes = try {
            Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return false
        }
        // Un enlace o una unión (isOther en Windows) llevaría a otra carpeta que no es esta.
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) return false
        val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java, NOFOLLOW_LINKS) ?: return false
        if (view.owner != accounts.administrators) return false
        val acl = view.acl
        return acl.isNotEmpty() && acl.all { it.principal() in accounts.allowed } && isInheritanceBlocked(directory)
    }

    /**
     * Java fija la lista sin marcarla como protegida: un cambio de permisos en `ProgramData` volvería a heredarse y
     * podría dar acceso a los usuarios.
     */
    private fun protectFromInheritance(directory: Path) {
        withDacl(directory) { dacl, _ ->
            val result = Advapi32.INSTANCE.SetNamedSecurityInfo(
                directory.toString(),
                AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                WinNT.DACL_SECURITY_INFORMATION or WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                null, null, dacl, null,
            )
            if (result != 0) throw IOException("SetNamedSecurityInfo falló con el código $result")
        }
    }

    private fun isInheritanceBlocked(directory: Path): Boolean = try {
        withDacl(directory) { _, descriptor ->
            // Descriptor autorrelativo: el campo Control es el WORD tras Revision y Sbz1.
            descriptor.getShort(2).toInt() and WinNT.SE_DACL_PROTECTED != 0
        }
    } catch (_: IOException) {
        false
    }

    private fun <T> withDacl(directory: Path, use: (dacl: com.sun.jna.Pointer, descriptor: com.sun.jna.Pointer) -> T): T {
        val dacl = PointerByReference()
        val descriptor = PointerByReference()
        val result = Advapi32.INSTANCE.GetNamedSecurityInfo(
            directory.toString(), AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT, WinNT.DACL_SECURITY_INFORMATION, null, null, dacl, null, descriptor,
        )
        if (result != 0) throw IOException("GetNamedSecurityInfo falló con el código $result")
        try {
            return use(dacl.value, descriptor.value)
        } finally {
            Kernel32.INSTANCE.LocalFree(descriptor.value)
        }
    }

    /** Aparta lo que haya en [path] (renombrar solo necesita permiso sobre la carpeta madre) e intenta borrarlo. */
    private fun moveAside(path: Path) {
        val retired = path.resolveSibling("${path.fileName}.apartada-${UUID.randomUUID()}")
        Files.move(path, retired)
        try {
            deleteWithoutFollowing(retired)
        } catch (_: IOException) {
            // Puede que su dueño no deje borrarla; ya no está en el camino, que es lo que importa.
        }
    }

    /** Borra sin entrar en enlaces ni uniones: una unión hacia System32 no debe vaciar System32. */
    private fun deleteWithoutFollowing(path: Path) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            Files.newDirectoryStream(path).use { children -> children.forEach(::deleteWithoutFollowing) }
        }
        Files.delete(path)
    }
}
