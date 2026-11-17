package org.uci.proxy.core.platform

import android.content.Context
import org.uci.proxy.core.port.KeyValueStore

class SharedPreferencesKeyValueStore(context: Context, fileName: String) : KeyValueStore {
    private val preferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    // commit() y no apply(): quien llama ya está fuera del hilo principal y necesita saber que quedó escrito.
    override fun putString(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "No se pudo guardar '$key'" }
    }

    override fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "No se pudo borrar '$key'" }
    }
}
