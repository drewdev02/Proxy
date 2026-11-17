package org.uci.proxy

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.android.ext.android.inject
import org.uci.proxy.core.platform.AndroidLocalNetworkPermission
import org.uci.proxy.core.platform.AndroidVpnConsent

class MainActivity : ComponentActivity() {
    // Punto de entrada que crea el sistema: aquí sí se obtienen las dependencias de Koin directamente.
    private val localNetworkPermission: AndroidLocalNetworkPermission by inject()
    private val vpnConsent: AndroidVpnConsent by inject()

    // Los diálogos del sistema solo los puede lanzar una Activity, y hay que registrarlos antes de onStart.
    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult?.invoke(granted)
        onPermissionResult = null
    }

    private var onVpnConsentResult: ((Boolean) -> Unit)? = null
    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onVpnConsentResult?.invoke(result.resultCode == Activity.RESULT_OK)
        onVpnConsentResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        localNetworkPermission.prompt.attach { onResult ->
            onPermissionResult = onResult
            permissionLauncher.launch(AndroidLocalNetworkPermission.PERMISSION)
        }
        vpnConsent.prompt.attach { onResult ->
            val intent = vpnConsent.consentIntent()
            if (intent == null) {
                onResult(true)
            } else {
                onVpnConsentResult = onResult
                vpnConsentLauncher.launch(intent)
            }
        }

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        localNetworkPermission.prompt.detach()
        vpnConsent.prompt.detach()
        super.onDestroy()
    }
}
