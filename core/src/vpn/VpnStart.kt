package org.uci.proxy.core.vpn

import kotlinx.serialization.Serializable

/** Todo lo que el motor necesita para salir por la VPN de un perfil. En escritorio viaja de la ventana al motor. */
@Serializable
data class VpnStart(val ovpn: String, val username: String, val password: String)
