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
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
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
        val transportMode = VpnTransportMode.fromWireValue(preferences[TRANSPORT_MODE].orEmpty())
        val disableIpv6 = preferences[DISABLE_IPV6] ?: false
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
            mtu = preferences[MTU] ?: transportMode.defaultMtu(),
            serverFailbackDelaySec = normalizeServerFailbackDelay(
                preferences[SERVER_FAILBACK_DELAY_SEC],
            ),
            transportMode = transportMode,
            prestartFullProbe = preferences[PRESTART_FULL_PROBE] ?: false,
            steadyStateQuickProbeEnabled = preferences[STEADY_STATE_QUICK_PROBE_ENABLED] ?: false,
            steadyStateBenchmarkEnabled = preferences[STEADY_STATE_BENCHMARK_ENABLED] ?: false,
            networkRescueProfile = readNetworkRescue(preferences),
            disableIpv6 = disableIpv6,
            packetFragmentPayloadBytes = preferences[PACKET_FRAGMENT_PAYLOAD_BYTES] ?: 0,
            disablePacketBatching = preferences[DISABLE_PACKET_BATCHING] ?: false,
            metrics = readMetrics(preferences),
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
            preservedConfigJson = preferences[PRESERVED_CONFIG_JSON].orEmpty(),
        )
    }

    override suspend fun save(profile: VpnProfile) {
        repositoryScope.async {
            runCatching { persistProfile(profile) }
                .onFailure {
                    Log.e(TAG, "Failed to persist profile.")
                }
                .getOrThrow()
        }.await()
    }

    private suspend fun persistProfile(profile: VpnProfile) {
        dataStore.edit { preferences ->
            preferences[PROFILE_NAME] = profile.profileName.trim().ifEmpty { "Primary" }
            preferences[RELAYS_JSON] = encodeRelays(profile.relays)
            preferences[USER_ID] = profile.userId.trim()
            preferences[SERVERS_JSON] = encodeServers(profile.servers)
            preferences[TUN_NAME] = profile.tunName.trim()
            preferences[DNS_SERVERS] = profile.dnsServers
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(",")
                .ifBlank { "1.1.1.1" }
            preferences[MTU] = profile.mtu.coerceIn(if (profile.disableIpv6) 100 else 1280, 1500)
            preferences[SERVER_FAILBACK_DELAY_SEC] = normalizeServerFailbackDelay(
                profile.serverFailbackDelaySec,
            )
            preferences[TRANSPORT_MODE] = profile.transportMode.wireValue
            preferences[PRESTART_FULL_PROBE] = profile.prestartFullProbe
            preferences[STEADY_STATE_QUICK_PROBE_ENABLED] = profile.steadyStateQuickProbeEnabled
            preferences[STEADY_STATE_BENCHMARK_ENABLED] = profile.steadyStateBenchmarkEnabled
            preferences[NETWORK_RESCUE_PROFILE] = profile.networkRescueProfile.wireValue
            preferences[DISABLE_IPV6] = profile.disableIpv6
            preferences[PACKET_FRAGMENT_PAYLOAD_BYTES] = profile.packetFragmentPayloadBytes
            preferences[DISABLE_PACKET_BATCHING] = profile.disablePacketBatching
            preferences[METRICS_JSON] = encodeMetrics(profile.metrics)
            preferences[SPLIT_MODE] = profile.splitTunnelMode.ordinal
            preferences[SELECTED_PACKAGES] = profile.selectedPackages
                .filter(String::isNotBlank)
                .filterNot(::isOwnPackage)
                .sorted()
                .joinToString("|")
            preferences[AUTO_RECONNECT] = profile.isAutoReconnectEnabled
            if (profile.preservedConfigJson.isBlank()) {
                preferences.remove(PRESERVED_CONFIG_JSON)
            } else {
                preferences[PRESERVED_CONFIG_JSON] = profile.preservedConfigJson
            }
        }
    }

    private fun isOwnPackage(packageName: String): Boolean {
        return packageName == context.packageName
    }

    private companion object {
        const val TAG = "ProfileRepo"
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val USER_ID = stringPreferencesKey("user_id")
        val RELAYS_JSON = stringPreferencesKey("relays_json")
        val SERVERS_JSON = stringPreferencesKey("servers_json")
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val MTU = intPreferencesKey("mtu")
        val SERVER_FAILBACK_DELAY_SEC = intPreferencesKey("server_failback_delay_sec")
        val TRANSPORT_MODE = stringPreferencesKey("transport_mode")
        val PRESTART_FULL_PROBE = booleanPreferencesKey("prestart_full_probe")
        val STEADY_STATE_QUICK_PROBE_ENABLED = booleanPreferencesKey("steady_state_quick_probe_enabled")
        val STEADY_STATE_BENCHMARK_ENABLED = booleanPreferencesKey("steady_state_benchmark_enabled")
        val NETWORK_RESCUE_PROFILE = stringPreferencesKey("network_rescue_profile")
        val DISABLE_IPV6 = booleanPreferencesKey("disable_ipv6")
        val PACKET_FRAGMENT_PAYLOAD_BYTES = intPreferencesKey("packet_fragment_payload_bytes")
        val DISABLE_PACKET_BATCHING = booleanPreferencesKey("disable_packet_batching")
        val METRICS_JSON = stringPreferencesKey("metrics_json")
        val SPLIT_MODE = intPreferencesKey("split_mode")
        val SELECTED_PACKAGES = stringPreferencesKey("selected_packages")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val PRESERVED_CONFIG_JSON = stringPreferencesKey("preserved_config_json")
    }

    private fun readRelays(preferences: Preferences): List<VpnRelayTarget> {
        val relaysJson = preferences[RELAYS_JSON].orEmpty()
        if (relaysJson.isBlank()) {
            return emptyList()
        }

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
                            relayKey = item.optString("relay_key").trim(),
                            transportPorts = item.optTransportPorts(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readServers(preferences: Preferences): List<VpnServerTarget> {
        val serversJson = preferences[SERVERS_JSON].orEmpty()
        if (serversJson.isBlank()) {
            return emptyList()
        }

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
                    .put("short_id", relay.shortId.coerceAtLeast(1))
                    .put("relay_key", relay.relayKey.trim())
                    .put("transport_ports", relay.transportPorts.toTransportPortsJson()),
            )
        }
        return array.toString()
    }

    private fun readMetrics(preferences: Preferences): VpnMetricsConfig {
        val metricsJson = preferences[METRICS_JSON].orEmpty()
        if (metricsJson.isBlank()) {
            return VpnMetricsConfig()
        }

        return runCatching {
            val json = JSONObject(metricsJson)
            VpnMetricsConfig(
                enabled = false,
                windowSeconds = json.optInt("window_seconds", 600).coerceAtLeast(1),
                fileEnabled = false,
                fileDir = "",
            )
        }.getOrDefault(VpnMetricsConfig())
    }

    private fun readNetworkRescue(preferences: Preferences): NetworkRescueProfile {
        preferences[NETWORK_RESCUE_PROFILE]
            ?.let(NetworkRescueProfile::fromWireValue)
            ?.let { return it }

        val preservedConfigJson = preferences[PRESERVED_CONFIG_JSON].orEmpty()
        if (preservedConfigJson.isBlank()) {
            return NetworkRescueProfile.OFF
        }

        return runCatching {
            val json = JSONObject(preservedConfigJson)
            val rescue = json.optJSONObject("network_rescue") ?: return@runCatching NetworkRescueProfile.OFF
            normalizeNetworkRescue(
                enabled = rescue.optFlexibleBoolean("enabled", default = false),
                profile = rescue.optString("profile"),
            )
        }.getOrDefault(NetworkRescueProfile.OFF)
    }

    private fun encodeMetrics(metrics: VpnMetricsConfig): String {
        return JSONObject()
            .put("enabled", false)
            .put("window_seconds", metrics.windowSeconds.coerceAtLeast(1))
            .put("file_enabled", false)
            .put("file_dir", "")
            .toString()
    }

    private fun normalizeNetworkRescue(
        enabled: Boolean,
        profile: String,
    ): NetworkRescueProfile {
        return NetworkRescueProfile.fromWireValue(profile)
            ?.takeIf { it != NetworkRescueProfile.OFF || !enabled }
            ?: if (enabled) {
                NetworkRescueProfile.STABLE
            } else {
                NetworkRescueProfile.OFF
            }
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

    private fun JSONObject.optTransportPorts(): Map<String, List<Int>> {
        val transportPorts = optJSONObject("transport_ports") ?: return emptyMap()
        return buildMap {
            transportPorts.keys().forEach { key ->
                val protocolId = key.trim().lowercase()
                if (protocolId.isBlank()) {
                    return@forEach
                }
                val ports = transportPorts.optJSONArray(key) ?: return@forEach
                val values = buildList {
                    for (index in 0 until ports.length()) {
                        val port = ports.optInt(index, -1)
                        if (port in 1..65535) {
                            add(port)
                        }
                    }
                }.distinct()
                if (values.isNotEmpty()) {
                    put(protocolId, values)
                }
            }
        }
    }

    private fun Map<String, List<Int>>.toTransportPortsJson(): JSONObject {
        val json = JSONObject()
        forEach { (protocolId, ports) ->
            val normalizedProtocolId = protocolId.trim().lowercase()
            val normalizedPorts = ports.filter { it in 1..65535 }.distinct()
            if (normalizedProtocolId.isNotBlank() && normalizedPorts.isNotEmpty()) {
                json.put(normalizedProtocolId, JSONArray(normalizedPorts))
            }
        }
        return json
    }

    private fun normalizeServerFailbackDelay(value: Int?): Int {
        return when (value) {
            null, 0 -> 60
            -1 -> -1
            else -> value.coerceAtLeast(1)
        }
    }

    private fun JSONObject.optFlexibleBoolean(
        name: String,
        default: Boolean,
    ): Boolean {
        return when (val value = opt(name)) {
            is Boolean -> value
            null,
            JSONObject.NULL,
            -> default
            else -> value.toString().trim().lowercase().let { normalized ->
                when (normalized) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> default
                }
            }
        }
    }
}
