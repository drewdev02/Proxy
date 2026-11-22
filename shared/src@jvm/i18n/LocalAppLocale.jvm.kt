package org.uci.proxy.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Escritorio: los recursos de Compose leen `Locale.getDefault()`, así que se cambia ese. */
actual object LocalAppLocale {
    private var default: Locale? = null
    private val LocalAppLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

    actual val current: String
        @Composable get() = LocalAppLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val system = default ?: Locale.getDefault().also { default = it }
        val new = if (value == null) system else Locale.forLanguageTag(value)
        Locale.setDefault(new)
        return LocalAppLocale.provides(new.toString())
    }
}
