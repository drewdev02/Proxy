package org.uci.proxy.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.uci.proxy.common.SystemSettings
import org.uci.proxy.common.SystemSettingsPage

/** Lleva a los permisos de la app o a las VPN del sistema, para arreglar un fallo que solo se arregla allí. */
class AndroidSystemSettings(private val context: Context) : SystemSettings {
    // Las dos pantallas existen en todo Android; si un fabricante quitó una, open() no hace nada.
    override fun canOpen(page: SystemSettingsPage) = true

    override fun open(page: SystemSettingsPage) {
        try {
            context.startActivity(intentFor(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Un fabricante que quitó esa pantalla: no hay a dónde ir, y el aviso sigue diciendo qué hacer.
        }
    }

    private fun intentFor(page: SystemSettingsPage): Intent = when (page) {
        SystemSettingsPage.APP -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        SystemSettingsPage.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
    }
}
