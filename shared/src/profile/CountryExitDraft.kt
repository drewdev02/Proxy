package org.uci.proxy.profile

import org.jetbrains.compose.resources.StringResource
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.OvpnConfigError
import org.uci.proxy.core.profile.ProfileField
import org.uci.proxy.core.vpn.OvpnConfigException
import org.uci.proxy.core.vpn.parseOvpn
import org.uci.proxy.core.vpn.tailscale.TailscaleExitNode

/**
 * La salida «Otro País» del perfil tal como se edita. Con [enabled] en false el perfil sale directo por el proxy.
 *
 * Como [RoutingDraft], lo que cambia el borrador son funciones puras que devuelven uno nuevo.
 */
data class VpnDraft(
    val enabled: Boolean = false,
    /** El motor elegido; los campos de cada uno se conservan al cambiar, por si se vuelve atrás. */
    val engine: CountryEngineKind = CountryEngineKind.OPENVPN,
    val fileName: String = "",
    /** El contenido del archivo ya leído; vacío si no se eligió uno válido. */
    val ovpn: String = "",
    val username: String = "",
    val password: String = "",
    /** Por qué no sirve el archivo elegido, para mostrarlo. */
    val fileError: OvpnConfigError? = null,
    /** Si el servidor VPN pide usuario y contraseña (casi todos los comerciales sí). */
    val needsCredentials: Boolean = true,
    val tailscale: TailscaleDraft = TailscaleDraft(),
) {
    /** Los campos que faltan o están mal para guardar el perfil con esta salida; vacío si está apagada o completa. */
    fun errors(): Set<ProfileField> = when {
        !enabled -> emptySet()
        engine == CountryEngineKind.TAILSCALE -> tailscale.errors()
        else -> buildSet {
            if (ovpn.isBlank()) add(ProfileField.VPN_FILE)
            if (needsCredentials && username.isBlank()) add(ProfileField.VPN_USERNAME)
        }
    }

    /** Lo que se guarda del perfil: la salida y sus secretos (van aparte, al almacén seguro); null si está apagada. */
    fun toExitAndSecrets(): Pair<CountryExit, CountryExitSecrets>? = when {
        !enabled -> null
        engine == CountryEngineKind.TAILSCALE -> tailscale.toExit() to CountryExitSecrets.Tailscale(tailscale.authKey.trim())
        else -> CountryExit.OpenVpn(fileName, username.trim()) to CountryExitSecrets.OpenVpn(ovpn, password)
    }

    /**
     * La persona eligió un archivo `.ovpn`: se comprueba enseguida, para avisar antes de guardar y no al conectar. Si
     * sirve, la salida queda encendida; si no, se guarda el motivo para mostrarlo y el contenido anterior se descarta.
     */
    fun withOvpnFile(fileName: String, text: String): VpnDraft = try {
        val config = parseOvpn(text)
        copy(enabled = true, fileName = fileName, ovpn = text, fileError = null, needsCredentials = config.needsCredentials)
    } catch (e: OvpnConfigException) {
        copy(fileName = fileName, ovpn = "", fileError = e.error)
    }

    /** Los secretos guardados del perfil, que se leen aparte y llegan después de lo demás. */
    fun withSecrets(secrets: CountryExitSecrets?): VpnDraft = when (secrets) {
        is CountryExitSecrets.OpenVpn -> copy(ovpn = secrets.ovpn, password = secrets.password, needsCredentials = ovpnNeedsCredentials(secrets.ovpn))
        is CountryExitSecrets.Tailscale -> copy(tailscale = tailscale.copy(authKey = secrets.authKey))
        null -> this
    }

    companion object {
        /** El borrador de un perfil que ya tiene Otro País. Los secretos llegan después ([withSecrets]). */
        fun from(exit: CountryExit): VpnDraft = when (exit) {
            is CountryExit.OpenVpn -> VpnDraft(enabled = true, engine = CountryEngineKind.OPENVPN, fileName = exit.fileName, username = exit.username)
            is CountryExit.Tailscale -> VpnDraft(
                enabled = true,
                engine = CountryEngineKind.TAILSCALE,
                tailscale = TailscaleDraft(controlUrl = exit.controlUrl, exitNode = exit.exitNode, hostname = exit.hostname),
            )
        }
    }
}

/** La configuración de Tailscale tal como se edita. */
data class TailscaleDraft(
    val controlUrl: String = CountryExit.Tailscale.DEFAULT_CONTROL_URL,
    val authKey: String = "",
    val exitNode: String = "",
    val hostname: String = CountryExit.Tailscale.DEFAULT_HOSTNAME,
    val search: ExitNodeSearch = ExitNodeSearch.Idle,
) {
    /** La dirección del servidor de control tiene que ser http(s) y la clave no puede estar vacía. */
    fun errors(): Set<ProfileField> = buildSet {
        val url = controlUrl.trim()
        if (!(url.startsWith("https://") || url.startsWith("http://")) || url.substringAfter("://").isBlank()) add(ProfileField.TAILSCALE_CONTROL_URL)
        if (authKey.isBlank()) add(ProfileField.TAILSCALE_AUTH_KEY)
    }

    fun toExit() = CountryExit.Tailscale(
        controlUrl = controlUrl.trim().trimEnd('/'),
        exitNode = exitNode.trim(),
        hostname = hostname.trim().ifEmpty { CountryExit.Tailscale.DEFAULT_HOSTNAME },
    )

    /**
     * Anota cómo terminó la búsqueda de nodos. Si entretanto se cambió algo que la anuló (otra red), el resultado ya no
     * vale y no cambia nada. Si encontró un solo nodo y no se había elegido ninguno, queda elegido.
     */
    fun withSearchOutcome(outcome: ExitNodeSearch): TailscaleDraft {
        if (search != ExitNodeSearch.Running) return this
        val only = (outcome as? ExitNodeSearch.Found)?.nodes?.singleOrNull()
        return copy(search = outcome, exitNode = if (only != null && exitNode.isBlank()) only.selector else exitNode)
    }
}

/** La búsqueda de nodos de salida de la red, que lanza el botón «Buscar nodos». */
sealed interface ExitNodeSearch {
    data object Idle : ExitNodeSearch

    data object Running : ExitNodeSearch

    data class Found(val nodes: List<TailscaleExitNode>) : ExitNodeSearch

    data class Failed(val reason: StringResource) : ExitNodeSearch
}

/** Un `.ovpn` sin `auth-user-pass` no pide credenciales; si no se puede leer, se piden por si acaso. */
private fun ovpnNeedsCredentials(ovpn: String): Boolean = try {
    parseOvpn(ovpn).needsCredentials
} catch (_: OvpnConfigException) {
    true
}
