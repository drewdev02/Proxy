package org.uci.proxy.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.uci.proxy.core.port.KeyValueStore

/** El idioma de la interfaz. [tag] es la etiqueta BCP 47; `null` es seguir al sistema. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    SPANISH("es"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag != null && it.tag == tag?.substringBefore('-') } ?: SYSTEM
    }
}

/** Qué idioma eligió la persona para la app, y cómo aplicarlo. */
interface AppLanguageSetting {
    val language: StateFlow<AppLanguage>

    /**
     * El idioma que la interfaz de Compose debe imponer: [AppLanguage.SYSTEM] si ya lo aplica el sistema (Android 13 o
     * superior, que guarda el idioma de cada app y reinicia la actividad al cambiarlo).
     */
    val composeOverride: StateFlow<AppLanguage>

    fun set(language: AppLanguage)
}

/** Lo guarda la propia app y lo impone en Compose: escritorio, iOS y Android anterior al 13. */
class StoredLanguageSetting(private val store: KeyValueStore) : AppLanguageSetting {
    private val state = MutableStateFlow(AppLanguage.fromTag(store.getString(KEY)))
    override val language: StateFlow<AppLanguage> = state.asStateFlow()
    override val composeOverride: StateFlow<AppLanguage> = state.asStateFlow()

    override fun set(language: AppLanguage) {
        val tag = language.tag
        if (tag == null) store.remove(KEY) else store.putString(KEY, tag)
        state.value = language
    }

    private companion object {
        const val KEY = "app.language"
    }
}

/**
 * El idioma que leen los recursos de Compose. No hay todavía una API común para cambiarlo en marcha: esta es la que
 * propone la guía de JetBrains (compose-resource-environment), con una versión por plataforma.
 */
expect object LocalAppLocale {
    val current: String @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** Aplica [language] a todo [content]; al cambiar, se vuelve a componer entero para que todos los textos se relean. */
@Composable
fun AppLanguageEnvironment(language: AppLanguage, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLocale provides language.tag) {
        key(language) { content() }
    }
}
