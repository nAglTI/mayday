package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
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
        root
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
            .put("discovery_relays", buildRelaysArray(profile))
            .put("transport", JSONObject().put("mode", profile.transportMode.wireValue))
            .put("servers", buildServersArray(profile))
            .put("split_tunnel", buildSplitTunnel(profile))

        return root.toString()
    }

    private fun buildRelaysArray(profile: VpnProfile): JSONArray {
        val array = JSONArray()
        profile.relays.forEachIndexed { index, relay ->
            val addr = relay.addr.trim()
            require(addr.isNotBlank()) { "Relay address is required." }
            val relayKey = relay.relayKey.trim()
            array.put(
                JSONObject()
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

    private companion object {
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
