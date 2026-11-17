package org.uci.proxy.core.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Consentimiento del sistema para que la app cree conexiones VPN. Android lo pide una vez
 * con su propio diálogo y lo recuerda hasta que otra app VPN toma el relevo.
 *
 * @param prompt muestra ese diálogo a través de la Activity en pantalla.
 */
class AndroidVpnConsent(
    private val context: Context,
    val prompt: ActivityPrompt,
) {
    /** Intent del diálogo de consentimiento, o null si la app ya está autorizada. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    suspend fun request(): Boolean = consentIntent() == null || prompt.show()
}
