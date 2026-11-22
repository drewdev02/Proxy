package org.uci.proxy.i18n

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android: los recursos de Compose leen el idioma de `LocalConfiguration`, así que se da una copia de la configuración con
 * el idioma elegido. Con `null` no se toca nada (el sistema ya aplica su idioma o el de la app).
 */
actual object LocalAppLocale {
    actual val current: String
        @Composable get() = LocalConfiguration.current.locales[0].toString()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        if (value == null) return LocalConfiguration.provides(configuration)
        val locale = Locale.forLanguageTag(value)
        Locale.setDefault(locale)
        return LocalConfiguration.provides(Configuration(configuration).apply { setLocale(locale) })
    }
}

/**
 * Desde Android 13 el idioma de cada app lo guarda el sistema ([LocaleManager]): se elige en la app o en
 * Ajustes > Apps > ProxyUCI > Idioma, y el sistema reinicia la actividad con él. Antes, lo guarda la app ([fallback]).
 */
class AndroidLanguageSetting(context: Context, private val fallback: StoredLanguageSetting) : AppLanguageSetting {
    private val localeManager: LocaleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.getSystemService(LocaleManager::class.java) else null

    private val state = MutableStateFlow(read())
    private val none = MutableStateFlow(AppLanguage.SYSTEM)

    init {
        // Si se cambia desde Ajustes del sistema con la app abierta, llega como un cambio de configuración.
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    state.value = read()
                }

                @Deprecated("Sustituido por onTrimMemory")
                override fun onLowMemory() = Unit
            },
        )
    }

    override val language: StateFlow<AppLanguage> = if (localeManager != null) state.asStateFlow() else fallback.language
    override val composeOverride: StateFlow<AppLanguage> = if (localeManager != null) none.asStateFlow() else fallback.composeOverride

    override fun set(language: AppLanguage) {
        val manager = localeManager ?: return fallback.set(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        }
        state.value = language
    }

    private fun read(): AppLanguage {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || localeManager == null) return AppLanguage.SYSTEM
        val locales = localeManager.applicationLocales
        return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales[0].toLanguageTag())
    }
}
