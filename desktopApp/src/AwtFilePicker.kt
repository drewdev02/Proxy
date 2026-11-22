package org.uci.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.uci.proxy.files.FilePicker
import org.uci.proxy.files.PickedFile
import org.uci.proxy.resources.Res
import org.uci.proxy.resources.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

/** El selector de archivos nativo de macOS y Windows, a través de AWT. */
class AwtFilePicker : FilePicker {
    override suspend fun pickText(): PickedFile? = withContext(Dispatchers.IO) {
        val title = getString(Res.string.ui_pick_ovpn_title)
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            // Windows usa el nombre como filtro; macOS, el filtro de nombres.
            file = "*.ovpn"
            setFilenameFilter { _, name -> name.endsWith(".ovpn", ignoreCase = true) }
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        val file = File(dialog.directory, name)
        // Un perfil de OpenVPN pesa unos pocos KB: un archivo enorme no es uno, y no se carga entero en memoria.
        if (file.length() > MAX_BYTES) return@withContext null
        try {
            PickedFile(name, file.readText())
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val MAX_BYTES = 1_000_000L
    }
}
