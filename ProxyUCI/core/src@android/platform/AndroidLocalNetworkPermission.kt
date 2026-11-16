package org.uci.proxy.core.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import org.uci.proxy.core.port.LocalNetworkPermission
import kotlin.coroutines.resume

/**
 * Pide `ACCESS_LOCAL_NETWORK` en Android 17 o superior.
 *
 * Mostrar el diálogo del sistema requiere una Activity, así que la que esté en pantalla se
 * registra con [attach] y se retira con [detach].
 */
class AndroidLocalNetworkPermission(private val context: Context) : LocalNetworkPermission {
    /** Lanza el diálogo del sistema y avisa del resultado. */
    fun interface Prompt {
        fun show(onResult: (granted: Boolean) -> Unit)
    }

    private var prompt: Prompt? = null
    private var pending: ((Boolean) -> Unit)? = null

    fun attach(prompt: Prompt) {
        this.prompt = prompt
    }

    fun detach() {
        prompt = null
        // Sin Activity ya no llegará respuesta: quien esperaba recibe una negativa en vez de quedarse colgado.
        pending?.invoke(false)
    }

    override suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < FIRST_API_THAT_REQUIRES_IT) return true
        if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        val currentPrompt = prompt ?: return false

        return suspendCancellableCoroutine { continuation ->
            val onResult: (Boolean) -> Unit = { granted ->
                pending = null
                if (continuation.isActive) continuation.resume(granted)
            }
            pending = onResult
            continuation.invokeOnCancellation { pending = null }
            currentPrompt.show(onResult)
        }
    }

    companion object {
        // Como texto y número literales: las constantes solo existen al compilar contra el SDK 37.
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val FIRST_API_THAT_REQUIRES_IT = 37
    }
}
