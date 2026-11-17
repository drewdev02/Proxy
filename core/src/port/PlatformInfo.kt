package org.uci.proxy.core.port

enum class PlatformKind(val displayName: String) {
    ANDROID("Android"),
    IOS("iOS"),
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
}

/**
 * Identifica la plataforma en la que corre la app.
 *
 * Es para la raíz de composición, diagnósticos y registros. No debe usarse para ramificar
 * comportamiento: para eso se pregunta por la capacidad concreta en el puerto correspondiente.
 */
interface PlatformInfo {
    val kind: PlatformKind
    val osVersion: String

    val isDesktop: Boolean
        get() = kind == PlatformKind.WINDOWS || kind == PlatformKind.MACOS || kind == PlatformKind.LINUX
}
