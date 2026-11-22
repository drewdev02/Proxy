package org.uci.proxy.common

/** Una página de los ajustes del sistema a la que la app puede llevar para arreglar un fallo. */
enum class SystemSettingsPage {
    /** Los permisos de la app (por ejemplo, el de red local). */
    APP,

    /** Las VPN del sistema (por ejemplo, para quitar otra VPN siempre activa). */
    VPN,
}

/** Abre los ajustes del sistema donde la plataforma lo permite (Android); en el resto no hay a dónde llevar. */
interface SystemSettings {
    fun canOpen(page: SystemSettingsPage): Boolean

    fun open(page: SystemSettingsPage)

    object None : SystemSettings {
        override fun canOpen(page: SystemSettingsPage) = false

        override fun open(page: SystemSettingsPage) = Unit
    }
}
