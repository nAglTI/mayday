package org.debs.mayday.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnServerTarget
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultVpnProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : VpnProfileRepository {

    override val profile: Flow<VpnProfile> = dataStore.data.map { preferences ->
        VpnProfile(
            profileName = preferences[PROFILE_NAME] ?: "Primary",
            relayHost = preferences[RELAY_HOST].orEmpty(),
            relayPort = preferences[RELAY_PORT] ?: 443,
            userId = preferences[USER_ID].orEmpty(),
            servers = readServers(preferences),
            tunName = preferences[TUN_NAME].orEmpty(),
            dnsServers = preferences[DNS_SERVERS]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.ifEmpty { listOf("1.1.1.1") }
                ?: listOf("1.1.1.1"),
            mtu = preferences[MTU] ?: 1420,
            splitTunnelMode = SplitTunnelMode.entries.getOrElse(
                preferences[SPLIT_MODE] ?: SplitTunnelMode.DISABLED.ordinal,
            ) { SplitTunnelMode.DISABLED },
            selectedPackages = preferences[SELECTED_PACKAGES]
                ?.split('|')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty(),
            isAutoReconnectEnabled = preferences[AUTO_RECONNECT] ?: true,
        )
    }

    override suspend fun save(profile: VpnProfile) {
        dataStore.edit { preferences ->
            preferences[PROFILE_NAME] = profile.profileName.trim().ifEmpty { "Primary" }
            preferences[RELAY_HOST] = profile.relayHost.trim()
            preferences[RELAY_PORT] = profile.relayPort.coerceIn(1, 65535)
            preferences[USER_ID] = profile.userId.trim()
            preferences[SERVERS_JSON] = encodeServers(profile.servers)
            profile.servers.firstOrNull()?.let { server ->
                preferences[SERVER_ID] = server.id.trim()
                preferences[SERVER_KEY] = server.key.trim()
                preferences[SERVER_PRIORITY] = server.priority.coerceAtLeast(1)
            } ?: run {
                preferences.remove(SERVER_ID)
                preferences.remove(SERVER_KEY)
                preferences.remove(SERVER_PRIORITY)
            }
            preferences[TUN_NAME] = profile.tunName.trim()
            preferences[DNS_SERVERS] = profile.dnsServers
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(",")
                .ifBlank { "1.1.1.1" }
            preferences[MTU] = profile.mtu.coerceIn(1280, 1500)
            preferences[SPLIT_MODE] = profile.splitTunnelMode.ordinal
            preferences[SELECTED_PACKAGES] = profile.selectedPackages
                .filter(String::isNotBlank)
                .sorted()
                .joinToString("|")
            preferences[AUTO_RECONNECT] = profile.isAutoReconnectEnabled
        }
    }

    private companion object {
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val RELAY_HOST = stringPreferencesKey("relay_host")
        val RELAY_PORT = intPreferencesKey("relay_port")
        val USER_ID = stringPreferencesKey("user_id")
        val SERVERS_JSON = stringPreferencesKey("servers_json")
        val SERVER_ID = stringPreferencesKey("server_id")
        val SERVER_KEY = stringPreferencesKey("server_key")
        val SERVER_PRIORITY = intPreferencesKey("server_priority")
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val MTU = intPreferencesKey("mtu")
        val SPLIT_MODE = intPreferencesKey("split_mode")
        val SELECTED_PACKAGES = stringPreferencesKey("selected_packages")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    }

    private fun readServers(preferences: Preferences): List<VpnServerTarget> {
        val serversJson = preferences[SERVERS_JSON].orEmpty()
        if (serversJson.isNotBlank()) {
            return runCatching {
                val array = JSONArray(serversJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            VpnServerTarget(
                                id = item.optString("id"),
                                key = item.optString("key"),
                                priority = item.optInt("priority", 1),
                            ),
                        )
                    }
                }.filter { it.id.isNotBlank() && it.key.isNotBlank() }
            }.getOrDefault(emptyList())
        }

        val legacyId = preferences[SERVER_ID].orEmpty()
        val legacyKey = preferences[SERVER_KEY].orEmpty()
        if (legacyId.isBlank() || legacyKey.isBlank()) {
            return emptyList()
        }

        return listOf(
            VpnServerTarget(
                id = legacyId,
                key = legacyKey,
                priority = preferences[SERVER_PRIORITY] ?: 1,
            ),
        )
    }

    private fun encodeServers(servers: List<VpnServerTarget>): String {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id.trim())
                    .put("key", server.key.trim())
                    .put("priority", server.priority.coerceAtLeast(1)),
            )
        }
        return array.toString()
    }
}
