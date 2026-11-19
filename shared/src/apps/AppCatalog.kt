package org.uci.proxy.apps

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** Una app instalada que se puede elegir para una regla de salida. */
class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/** Las apps instaladas en el equipo. Solo Android lo aporta: es donde las reglas por app se aplican. */
interface AppCatalog {
    /** Las apps que se abren desde el lanzador, por orden alfabético. */
    suspend fun installedApps(): List<InstalledApp>
}

/** Null donde no hay reglas por app (escritorio, iOS): la interfaz no ofrece elegir apps. */
val LocalAppCatalog = staticCompositionLocalOf<AppCatalog?> { null }
