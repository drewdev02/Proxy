package org.uci.proxy.core.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.uci.proxy.core.port.LocalNetworkPermission

/**
 * Pide `ACCESS_LOCAL_NETWORK` en Android 17 o superior.
 *
 * @param prompt muestra el diálogo de permiso del sistema a través de la Activity en pantalla.
 */
class AndroidLocalNetworkPermission(
    private val context: Context,
    val prompt: ActivityPrompt,
) : LocalNetworkPermission {
    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < FIRST_API_THAT_REQUIRES_IT) return true
        if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        return prompt.show()
    }

    companion object {
        // Como texto y número literales: las constantes solo existen al compilar contra el SDK 37.
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val FIRST_API_THAT_REQUIRES_IT = 37
    }
}
