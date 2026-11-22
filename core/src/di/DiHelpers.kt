package org.uci.proxy.core.di

import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.uci.proxy.core.vpn.OpenVpnCrypto
import org.uci.proxy.core.vpn.UnavailableOpenVpnCrypto
import org.uci.proxy.core.vpn.tailscale.TailscaleBridge

/**
 * Los conectores de proxy de la app, uno por protocolo (`List<ProxyConnector>`). Va con nombre porque Koin no distingue
 * los tipos genéricos: cualquier otra `List` registrada chocaría con esta.
 */
val ProxyConnectors = named("proxyConnectors")

/** Si esta plataforma tiene cliente OpenVPN propio (iOS enlaza la criptografía «no disponible»). */
internal fun Scope.openVpnAvailable(): Boolean = getOrNull<OpenVpnCrypto>().let { it != null && it !is UnavailableOpenVpnCrypto }

/** La librería de Tailscale si la plataforma la enlazó; si no, la que dice que no está. */
internal fun Scope.tailscaleBridge(): TailscaleBridge = getOrNull<TailscaleBridge>() ?: TailscaleBridge.Unavailable
