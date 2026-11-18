package org.uci.proxy.files

import androidx.compose.runtime.staticCompositionLocalOf

/** Un archivo de texto que la persona eligió. */
class PickedFile(val name: String, val text: String)

/** Deja elegir un archivo del equipo. Cada aplicación aporta el suyo: el selector de archivos es del sistema. */
interface FilePicker {
    /** @return el archivo elegido, o null si la persona cancela o no se pudo leer. */
    suspend fun pickText(): PickedFile?
}

/** Null donde no hay selector (iOS): la interfaz oculta entonces lo que lo necesita. */
val LocalFilePicker = staticCompositionLocalOf<FilePicker?> { null }
