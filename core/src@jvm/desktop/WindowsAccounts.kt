package org.uci.proxy.core.desktop

import com.sun.jna.platform.win32.Advapi32Util
import java.io.IOException
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * Cuentas de Windows por su SID conocido. Los nombres cambian con el idioma (en español el grupo es «Administradores»),
 * así que buscarlos por nombre falla fuera de un Windows en inglés.
 */
object WindowsAccounts {
    const val ADMINISTRATORS_SID = "S-1-5-32-544"
    const val SYSTEM_SID = "S-1-5-18"

    /** El nombre local de [sid] con su dominio (`BUILTIN\Administradores`), o null si Windows no lo resuelve. */
    fun localNameOf(sid: String): String? = try {
        val account = Advapi32Util.getAccountBySid(sid)
        if (account.domain.isNullOrEmpty()) account.name else "${account.domain}\\${account.name}"
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        // Sin Advapi32 (fuera de Windows) no hay a quién preguntar.
        null
    }

    /** El grupo de Administradores de este equipo, se llame como se llame, o null si no se encuentra. */
    fun administrators(lookup: UserPrincipalLookupService, nameOf: (String) -> String? = ::localNameOf): UserPrincipal? =
        principal(ADMINISTRATORS_SID, nameOf) { lookup.lookupPrincipalByGroupName(it) }

    /** La cuenta SYSTEM, o null si no se encuentra. */
    fun system(lookup: UserPrincipalLookupService, nameOf: (String) -> String? = ::localNameOf): UserPrincipal? =
        // SYSTEM es un «grupo conocido» para Windows, pero Java solo lo encuentra buscándolo como cuenta.
        principal(SYSTEM_SID, nameOf) { lookup.lookupPrincipalByName(it) }

    private fun principal(sid: String, nameOf: (String) -> String?, find: (String) -> UserPrincipal): UserPrincipal? {
        val name = nameOf(sid) ?: return null
        return try {
            find(name)
        } catch (e: IOException) {
            null
        }
    }
}
