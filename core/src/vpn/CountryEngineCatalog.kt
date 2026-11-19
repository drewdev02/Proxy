package org.uci.proxy.core.vpn

import org.uci.proxy.core.model.CountryEngineKind
import org.uci.proxy.core.model.CountryExit
import org.uci.proxy.core.model.CountryExitSecrets
import org.uci.proxy.core.model.ProxyProfile
import org.uci.proxy.core.port.TunnelFailure
import org.uci.proxy.core.settings.AppSettings
import org.uci.proxy.core.vpn.tailscale.ExitNodeListing
import org.uci.proxy.core.vpn.tailscale.TailscaleEngine

/**
 * Lo que la interfaz necesita saber de los motores de «Otro País»: cuáles hay en este equipo y, para Tailscale, qué
 * nodos de salida ofrece una red. Vive en el proceso de la interfaz (en escritorio, la ventana, que no ejecuta el túnel).
 */
interface CountryEngineCatalog {
    /** Los motores que pueden usarse en este equipo. */
    val available: List<CountryEngineKind>

    /** Busca los nodos de salida de la red de [exit], llegando a ella por el proxy del perfil. */
    suspend fun listTailscaleExitNodes(
        proxy: ProxyProfile,
        proxyPassword: String,
        settings: AppSettings,
        exit: CountryExit.Tailscale,
        secrets: CountryExitSecrets.Tailscale,
    ): ExitNodeListing

    /** Solo OpenVPN, sin búsqueda de nodos: para vistas previas y pruebas. */
    object OpenVpnOnly : CountryEngineCatalog {
        override val available = listOf(CountryEngineKind.OPENVPN)

        override suspend fun listTailscaleExitNodes(
            proxy: ProxyProfile,
            proxyPassword: String,
            settings: AppSettings,
            exit: CountryExit.Tailscale,
            secrets: CountryExitSecrets.Tailscale,
        ): ExitNodeListing = ExitNodeListing.Failed(TunnelFailure.ENGINE_UNAVAILABLE, "Tailscale no está disponible")
    }
}

/** El catálogo real: OpenVPN si hay cliente, Tailscale si su librería cargó. */
class EngineCatalog(
    openVpnAvailable: Boolean,
    private val tailscale: TailscaleEngine?,
) : CountryEngineCatalog {
    override val available: List<CountryEngineKind> by lazy {
        buildList {
            if (openVpnAvailable) add(CountryEngineKind.OPENVPN)
            if (tailscale?.isAvailable == true) add(CountryEngineKind.TAILSCALE)
        }
    }

    override suspend fun listTailscaleExitNodes(
        proxy: ProxyProfile,
        proxyPassword: String,
        settings: AppSettings,
        exit: CountryExit.Tailscale,
        secrets: CountryExitSecrets.Tailscale,
    ): ExitNodeListing {
        val engine = tailscale ?: return ExitNodeListing.Failed(TunnelFailure.ENGINE_UNAVAILABLE, "Tailscale no está disponible en este equipo")
        return engine.listExitNodes(ExitContext(proxy, proxyPassword, settings), exit, secrets)
    }
}
