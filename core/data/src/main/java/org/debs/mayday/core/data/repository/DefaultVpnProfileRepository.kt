package org.debs.mayday.core.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultVpnProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
) : VpnProfileRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val profile: Flow<VpnProfile> = dataStore.data.map { preferences ->
        VpnProfile(
            profileName = preferences[PROFILE_NAME] ?: "Primary",
            relays = readRelays(preferences),
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
                ?.filterNot(::isOwnPackage)
                ?.toSet()
                .orEmpty(),
            isAutoReconnectEnabled = preferences[AUTO_RECONNECT] ?: true,
        )
    }

    override suspend fun save(profile: VpnProfile) {
        repositoryScope.async {
            runCatching { persistProfile(profile) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to persist profile.", error)
                }
                .getOrThrow()
        }.await()
    }

    private suspend fun persistProfile(profile: VpnProfile) {
        dataStore.edit { preferences ->
            preferences[PROFILE_NAME] = profile.profileName.trim().ifEmpty { "Primary" }
            preferences[RELAYS_JSON] = encodeRelays(profile.relays)
            val primaryRelay = profile.relays.firstOrNull()
            val legacyRelay = primaryRelay?.addr.orEmpty().parseRelayAddress()
            preferences[RELAY_HOST] = legacyRelay?.first.orEmpty()
            preferences[RELAY_PORT] = legacyRelay?.second ?: 443
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
                .filterNot(::isOwnPackage)
                .sorted()
                .joinToString("|")
            preferences[AUTO_RECONNECT] = profile.isAutoReconnectEnabled
        }
    }

    private fun isOwnPackage(packageName: String): Boolean {
        return packageName == context.packageName
    }

    private companion object {
        const val TAG = "ProfileRepo"
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val RELAY_HOST = stringPreferencesKey("relay_host")
        val RELAY_PORT = intPreferencesKey("relay_port")
        val USER_ID = stringPreferencesKey("user_id")
        val RELAYS_JSON = stringPreferencesKey("relays_json")
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

    private fun readRelays(preferences: Preferences): List<VpnRelayTarget> {
        val relaysJson = preferences[RELAYS_JSON].orEmpty()
        if (relaysJson.isNotBlank()) {
            return runCatching {
                val array = JSONArray(relaysJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val addr = item.optString("addr").trim()
                        if (addr.isBlank()) {
                            continue
                        }
                        add(
                            VpnRelayTarget(
                                id = item.optString("id").trim().ifBlank { "relay-${index + 1}" },
                                addr = addr,
                                shortId = item.optInt("short_id", index + 1).coerceAtLeast(1),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        val legacyHost = preferences[RELAY_HOST].orEmpty().trim()
        if (legacyHost.isBlank()) {
            return emptyList()
        }

        return listOf(
            VpnRelayTarget(
                id = "relay-1",
                addr = "$legacyHost:${preferences[RELAY_PORT] ?: 443}",
                shortId = 1,
            ),
        )
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

    private fun encodeRelays(relays: List<VpnRelayTarget>): String {
        val array = JSONArray()
        relays.forEachIndexed { index, relay ->
            val addr = relay.addr.trim()
            if (addr.isBlank()) {
                return@forEachIndexed
            }
            array.put(
                JSONObject()
                    .put("id", relay.id.trim().ifBlank { "relay-${index + 1}" })
                    .put("addr", addr)
                    .put("short_id", relay.shortId.coerceAtLeast(1)),
            )
        }
        return array.toString()
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

    private fun String.parseRelayAddress(): Pair<String, Int>? {
        val trimmed = trim()
        if (trimmed.isBlank()) {
            return null
        }

        val separatorIndex = trimmed.lastIndexOf(':')
        if (separatorIndex <= 0 || separatorIndex == trimmed.lastIndex) {
            return null
        }

        val host = trimmed.substring(0, separatorIndex).trim()
        val port = trimmed.substring(separatorIndex + 1).trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: return null
        if (host.isBlank()) {
            return null
        }
        return host to port
    }
}
