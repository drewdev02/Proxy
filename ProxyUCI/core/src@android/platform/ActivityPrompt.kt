package org.uci.proxy.core.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Puente hacia un diálogo del sistema que solo una Activity puede mostrar.
 *
 * La Activity en pantalla se registra con [attach] y se retira con [detach]; quien necesita el
 * diálogo llama a [show] y espera el resultado sin conocer la Activity.
 */
class ActivityPrompt {
    /** Lanza el diálogo y avisa del resultado. */
    fun interface Launcher {
        fun launch(onResult: (accepted: Boolean) -> Unit)
    }

    private var launcher: Launcher? = null
    private var pending: ((Boolean) -> Unit)? = null

    fun attach(launcher: Launcher) {
        this.launcher = launcher
    }

    fun detach() {
        launcher = null
        // Sin Activity ya no llegará respuesta: quien esperaba recibe una negativa en vez de quedarse colgado.
        pending?.invoke(false)
    }

    /** Devuelve false si no hay ninguna Activity que pueda mostrar el diálogo. */
    suspend fun show(): Boolean {
        val current = launcher ?: return false
        return suspendCancellableCoroutine { continuation ->
            val onResult: (Boolean) -> Unit = { accepted ->
                pending = null
                if (continuation.isActive) continuation.resume(accepted)
            }
            pending = onResult
            continuation.invokeOnCancellation { pending = null }
            current.launch(onResult)
        }
    }
}
