package org.uci.proxy.core.data

import org.uci.proxy.core.port.ActiveProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.uci.proxy.core.port.KeyValueStore

class StoredActiveProfileStore(private val keyValueStore: KeyValueStore) : ActiveProfileStore {
    private val state = MutableStateFlow(keyValueStore.getString(ACTIVE_PROFILE_KEY))

    override val activeProfileId: StateFlow<String?> = state.asStateFlow()

    override fun select(profileId: String) {
        keyValueStore.putString(ACTIVE_PROFILE_KEY, profileId)
        state.value = profileId
    }

    companion object {
        const val ACTIVE_PROFILE_KEY = "active_profile_id"
    }
}
