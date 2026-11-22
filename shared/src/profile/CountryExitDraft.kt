package org.uci.proxy.profile

import org.jetbrains.compose.resources.StringResource
import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.OvpnConfigError
import org.uci.proxy.core.vpn.tailscale.TailscaleExitNode

/** La salida «Otro País» del perfil tal como se edita. Con [enabled] en false el perfil sale directo por el proxy. */
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
)

/** La configuración de Tailscale tal como se edita. */
data class TailscaleDraft(
    val controlUrl: String = CountryExit.Tailscale.DEFAULT_CONTROL_URL,
    val authKey: String = "",
    val exitNode: String = "",
    val hostname: String = CountryExit.Tailscale.DEFAULT_HOSTNAME,
    val search: ExitNodeSearch = ExitNodeSearch.Idle,
)

/** La búsqueda de nodos de salida de la red, que lanza el botón «Buscar nodos». */
sealed interface ExitNodeSearch {
    data object Idle : ExitNodeSearch

    data object Running : ExitNodeSearch

    data class Found(val nodes: List<TailscaleExitNode>) : ExitNodeSearch

    data class Failed(val reason: StringResource) : ExitNodeSearch
}
