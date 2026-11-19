package org.uci.proxy.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Las apps que se abren desde el lanzador, con su nombre e icono. Basta con `<queries>` para el lanzador en el
 * manifiesto: no hace falta el permiso de ver todos los paquetes.
 */
class PackageManagerAppCatalog(private val context: Context) : AppCatalog {
    private var cached: List<InstalledApp>? = null

    override suspend fun installedApps(): List<InstalledApp> = cached ?: withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            // La propia app nunca pasa por su túnel.
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = runCatching { info.loadIcon(packageManager).toImageBitmap() }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .also { cached = it }
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, ICON_PX, ICON_PX)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val ICON_PX = 96
    }
}
