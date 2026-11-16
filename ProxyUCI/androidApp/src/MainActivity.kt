package org.uci.proxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.android.ext.android.inject
import org.uci.proxy.core.platform.AndroidLocalNetworkPermission

class MainActivity : ComponentActivity() {
    // Punto de entrada que crea el sistema: aquí sí se obtiene la dependencia de Koin directamente.
    private val localNetworkPermission: AndroidLocalNetworkPermission by inject()

    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult?.invoke(granted)
        onPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        localNetworkPermission.attach { onResult ->
            onPermissionResult = onResult
            permissionLauncher.launch(AndroidLocalNetworkPermission.PERMISSION)
        }
        setContent {
            App()
        }
    }

    override fun onDestroy() {
        localNetworkPermission.detach()
        super.onDestroy()
    }
}
