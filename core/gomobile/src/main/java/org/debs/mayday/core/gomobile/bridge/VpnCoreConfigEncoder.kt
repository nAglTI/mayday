package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
import org.debs.mayday.core.model.VpnRelayTarget
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnCoreConfigEncoder @Inject constructor() {

    fun encode(profile: VpnProfile): String {
        val userId = profile.userId.trim().toLongOrNull()

        require(profile.relays.isNotEmpty()) { "At least one relay is required." }
        require(userId != null && userId > 0) { "User ID must be a positive integer." }
        require(profile.servers.isNotEmpty()) { "At least one server is required." }
        require(VpnProfileCompatibilityValidator.firstIssue(profile) == null) {
            "Saved VPN config is not compatible with the current core. Please get a new import key."
        }

        val root = profile.preservedConfigJson
            .takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: JSONObject()

        root.remove("relay")
        root.remove("relays")
        val preservedRelays = root.optJSONArray("discovery_relays")
        val preservedTransport = root.optJSONObject("transport")
        root
            .put("config_version", SUPPORTED_CONFIG_VERSION)
            .put("user_id", userId)
            .put("server_failback_delay_sec", normalizeServerFailbackDelay(profile.serverFailbackDelaySec))
            .put("prestart_full_probe", profile.prestartFullProbe)
            .put("steady_state_quick_probe_enabled", profile.steadyStateQuickProbeEnabled)
            .put("steady_state_benchmark_enabled", profile.steadyStateBenchmarkEnabled)
            .put("network_rescue", buildNetworkRescue(profile))
            .put("disable_ipv6", profile.disableIpv6)
            .put("tunnel_mtu", normalizeTunnelMtu(profile))
            .put("packet_fragment_payload_bytes", normalizePacketFragmentPayloadBytes(profile))
            .put("disable_packet_batching", profile.disablePacketBatching)
            .put("packet_padding_min_bytes", normalizePacketPaddingMinBytes(profile))
            .put("packet_padding_max_bytes", normalizePacketPaddingMaxBytes(profile))
            .put("metrics", buildClientMetrics(profile.metrics))
            .put("discovery_relays", buildRelaysArray(profile, preservedRelays))
            .put("transport", buildTransport(profile, preservedTransport))
            .put("servers", buildServersArray(profile))
            .put("split_tunnel", buildSplitTunnel(profile))

        return root.toString()
    }

    private fun buildRelaysArray(
        profile: VpnProfile,
        preservedRelays: JSONArray?,
    ): JSONArray {
        val array = JSONArray()
        profile.relays.forEachIndexed { index, relay ->
            val addr = relay.addr.trim()
            require(addr.isNotBlank()) { "Relay address is required." }
            val relayKey = relay.relayKey.trim()
            val endpointAddrs = relay.endpointAddrs.normalizedEndpointAddrs()
            val relayJson = preservedRelays.findPreservedRelay(index, relay) ?: JSONObject()
            if (endpointAddrs.isNotEmpty()) {
                relayJson.put("endpoint_addrs", JSONArray(endpointAddrs))
            }
            array.put(
                relayJson
                    .put("id", relay.id.trim().ifBlank { "relay-${index + 1}" })
                    .put("addr", addr)
                    .put("short_id", relay.shortId.coerceAtLeast(1))
                    .apply {
                        if (relayKey.isNotBlank()) {
                            put("relay_key", relayKey)
                        }
                    }
                    .put("transport_ports", relay.transportPorts.toTransportPortsJson()),
            )
        }
        return array
    }

    private fun buildTransport(
        profile: VpnProfile,
        preservedTransport: JSONObject?,
    ): JSONObject {
        val transport = preservedTransport?.let { JSONObject(it.toString()) } ?: JSONObject()
        return transport.put("mode", profile.transportMode.wireValue)
    }

    private fun buildServersArray(profile: VpnProfile): JSONArray {
        val array = JSONArray()
        profile.servers.forEach { server ->
            require(server.id.isNotBlank()) { "Server ID is required." }
            require(server.key.isNotBlank()) { "Server key is required." }
            require(SERVER_KEY_PATTERN.matches(server.key.trim())) {
                "Server key must be a 64-character hex string."
            }
            array.put(
                JSONObject()
                    .put("id", server.id.trim())
                    .put("key", server.key.trim())
                    .put("priority", server.priority.coerceAtLeast(1)),
            )
        }
        return array
    }

    private fun buildSplitTunnel(profile: VpnProfile): JSONObject {
        val enabled = profile.splitTunnelMode != SplitTunnelMode.DISABLED
        val existing = profile.preservedConfigJson
            .takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { JSONObject(raw).optJSONObject("split_tunnel") }.getOrNull() }
            ?: JSONObject()
        existing.remove("mode")
        if (!existing.has("apps_win")) {
            existing.put("apps_win", JSONArray(emptyList<String>()))
        }
        return existing
            .put("enabled", enabled)
            .put("apps_mode", profile.splitTunnelMode.toWireValue())
            .put(
                "apps_android",
                JSONArray(
                    if (enabled) {
                        profile.selectedPackages.sorted()
                    } else {
                        emptyList<String>()
                    },
                ),
            )
    }

    private fun buildNetworkRescue(profile: VpnProfile): JSONObject {
        return JSONObject()
            .put("enabled", profile.networkRescueProfile.isEnabled)
            .put("profile", profile.networkRescueProfile.wireValue)
    }

    private fun buildClientMetrics(metrics: VpnMetricsConfig): JSONObject {
        return JSONObject()
            .put("enabled", metrics.enabled)
            .put("window_seconds", metrics.windowSeconds.coerceAtLeast(1))
            .put("file_enabled", false)
            .put("file_dir", "")
    }

    private fun SplitTunnelMode.toWireValue(): String = when (this) {
        SplitTunnelMode.DISABLED -> "whitelist"
        SplitTunnelMode.ONLY_SELECTED -> "whitelist"
        SplitTunnelMode.EXCLUDE_SELECTED -> "blacklist"
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

    private fun JSONArray?.findPreservedRelay(
        index: Int,
        relay: VpnRelayTarget,
    ): JSONObject? {
        val array = this ?: return null
        val relayId = relay.id.trim()
        if (relayId.isNotBlank()) {
            for (candidateIndex in 0 until array.length()) {
                val candidate = array.optJSONObject(candidateIndex) ?: continue
                if (candidate.optString("id").trim() == relayId) {
                    return JSONObject(candidate.toString())
                }
            }
        }

        val shortId = relay.shortId.coerceAtLeast(1)
        for (candidateIndex in 0 until array.length()) {
            val candidate = array.optJSONObject(candidateIndex) ?: continue
            if (candidate.optInt("short_id", -1) == shortId) {
                return JSONObject(candidate.toString())
            }
        }

        return array.optJSONObject(index)?.let { JSONObject(it.toString()) }
    }

    private fun List<String>.normalizedEndpointAddrs(): List<String> {
        return map(String::trim).filter(String::isNotBlank).distinct()
    }

    private fun normalizeServerFailbackDelay(value: Int): Int {
        require(value == -1 || value >= 0) {
            "server_failback_delay_sec must be -1, 0, or a positive integer."
        }
        return if (value == 0) 60 else value
    }

    private fun normalizeTunnelMtu(profile: VpnProfile): Int {
        val minMtu = if (profile.disableIpv6) 100 else 1280
        require(profile.mtu in minMtu..1500) {
            "tunnel_mtu must be ${minMtu}..1500 for the current IPv6 mode."
        }
        return profile.mtu
    }

    private fun normalizePacketFragmentPayloadBytes(profile: VpnProfile): Int {
        val value = profile.packetFragmentPayloadBytes
        require(value == 0 || value in 64..65536) {
            "packet_fragment_payload_bytes must be 0 or a value from 64 to 65536."
        }
        return value
    }

    private fun normalizePacketPaddingMinBytes(profile: VpnProfile): Int {
        require(isPacketPaddingValid(profile)) {
            "packet padding must be 0/0 or a random range from 0 to 1200 bytes."
        }
        return profile.packetPaddingMinBytes
    }

    private fun normalizePacketPaddingMaxBytes(profile: VpnProfile): Int {
        require(isPacketPaddingValid(profile)) {
            "packet padding must be 0/0 or a random range from 0 to 1200 bytes."
        }
        return profile.packetPaddingMaxBytes
    }

    private fun isPacketPaddingValid(profile: VpnProfile): Boolean {
        if (profile.packetPaddingMinBytes !in 0..1200 || profile.packetPaddingMaxBytes !in 0..1200) {
            return false
        }
        if (profile.packetPaddingMinBytes == 0 && profile.packetPaddingMaxBytes == 0) {
            return true
        }
        return profile.packetPaddingMinBytes < profile.packetPaddingMaxBytes
    }

    private companion object {
        const val SUPPORTED_CONFIG_VERSION = 1
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
