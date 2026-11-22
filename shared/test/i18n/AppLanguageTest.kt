package org.uci.proxy.i18n

import org.uci.proxy.fakes.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun `una etiqueta con región se reconoce por su idioma`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es"))
    }

    @Test
    fun `sin etiqueta o con un idioma que la app no tiene sigue al sistema`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("pt-BR"))
    }

    @Test
    fun `el idioma elegido se guarda y se recupera al volver a abrir la app`() {
        val store = InMemoryKeyValueStore()
        StoredLanguageSetting(store).set(AppLanguage.ENGLISH)

        val reopened = StoredLanguageSetting(store)

        assertEquals(AppLanguage.ENGLISH, reopened.language.value)
        assertEquals(AppLanguage.ENGLISH, reopened.composeOverride.value)
    }

    @Test
    fun `volver a seguir al sistema borra la elección`() {
        val store = InMemoryKeyValueStore()
        val setting = StoredLanguageSetting(store)
        setting.set(AppLanguage.SPANISH)

        setting.set(AppLanguage.SYSTEM)

        assertEquals(AppLanguage.SYSTEM, StoredLanguageSetting(store).language.value)
    }
}
