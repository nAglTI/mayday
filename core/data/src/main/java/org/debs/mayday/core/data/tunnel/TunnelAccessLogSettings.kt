package org.debs.mayday.core.data.tunnel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

internal interface TunnelAccessLogSettings {
    suspend fun readEnabled(): Boolean
    suspend fun writeEnabled(enabled: Boolean)
}

internal class DataStoreTunnelAccessLogSettings(
    private val dataStore: DataStore<Preferences>
) : TunnelAccessLogSettings {
    override suspend fun readEnabled(): Boolean = dataStore.data.first()[ENABLED] ?: false

    override suspend fun writeEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("tunnel_access_logging_enabled")
    }
}
