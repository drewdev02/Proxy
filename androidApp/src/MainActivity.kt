package org.uci.proxy

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.uci.proxy.apps.PackageManagerAppCatalog
import org.uci.proxy.core.platform.AndroidLocalNetworkPermission
import org.uci.proxy.core.platform.AndroidVpnConsent
import org.uci.proxy.files.FilePicker
import org.uci.proxy.files.PickedFile
import java.io.IOException
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    // Punto de entrada que crea el sistema: aquí sí se obtienen las dependencias de Koin directamente.
    private val localNetworkPermission: AndroidLocalNetworkPermission by inject()
    private val vpnConsent: AndroidVpnConsent by inject()

    // Las apps instaladas, para elegirlas en las reglas de salida.
    private val appCatalog by lazy { PackageManagerAppCatalog(applicationContext) }

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

    // El selector de archivos del sistema (para el .ovpn): también solo lo lanza una Activity.
    private var onFilePicked: ((Uri?) -> Unit)? = null
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onFilePicked?.invoke(uri)
        onFilePicked = null
    }
    private val filePicker = object : FilePicker {
        override suspend fun pickText(): PickedFile? {
            // Los .ovpn no tienen un tipo MIME propio, así que se ofrecen todos los archivos.
            val uri = suspendCancellableCoroutine<Uri?> { continuation ->
                onFilePicked = { continuation.resume(it) }
                continuation.invokeOnCancellation { onFilePicked = null }
                filePickerLauncher.launch(arrayOf("*/*"))
            } ?: return null
            return withContext(Dispatchers.IO) { readPicked(uri) }
        }
    }

    private fun readPicked(uri: Uri): PickedFile? = try {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "perfil.ovpn"
        // Un perfil de OpenVPN pesa unos pocos KB: se lee con un tope para no cargar un archivo enorme.
        val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_FILE_BYTES + 1) }
        if (bytes == null || bytes.size > MAX_FILE_BYTES) null else PickedFile(name, bytes.decodeToString())
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
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
            App(filePicker = filePicker, appCatalog = appCatalog)
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 1_000_000
    }

    override fun onDestroy() {
        localNetworkPermission.prompt.detach()
        vpnConsent.prompt.detach()
        super.onDestroy()
    }
}
