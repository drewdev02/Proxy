package org.uci.proxy.core.platform

import org.uci.proxy.core.port.KeyValueStore
import platform.Foundation.NSUserDefaults

class UserDefaultsKeyValueStore(private val defaults: NSUserDefaults) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
