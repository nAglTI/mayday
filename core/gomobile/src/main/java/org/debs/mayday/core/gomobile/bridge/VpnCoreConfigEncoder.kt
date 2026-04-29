package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
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

        val root = JSONObject()
            .put("relays", buildRelaysArray(profile))
            .put("user_id", userId)
            .put("tun_name", profile.tunName.trim().ifBlank { "VPN0" })
            .put("dns", profile.dnsServers.firstOrNull().orEmpty().ifBlank { "1.1.1.1" })
            .put("server_failback_delay_sec", normalizeServerFailbackDelay(profile.serverFailbackDelaySec))
            .put("transport", JSONObject().put("mode", profile.transportMode.wireValue))
            .put("metrics", buildMetrics(profile))
            .put("servers", buildServersArray(profile))
            .put("split_tunnel", buildSplitTunnel(profile))

        return root.toString()
    }

    private fun buildRelaysArray(profile: VpnProfile): JSONArray {
        val array = JSONArray()
        profile.relays.forEachIndexed { index, relay ->
            val addr = relay.addr.trim()
            require(addr.isNotBlank()) { "Relay address is required." }
            array.put(
                JSONObject()
                    .put("id", relay.id.trim().ifBlank { "relay-${index + 1}" })
                    .put("addr", addr)
                    .put("short_id", relay.shortId.coerceAtLeast(1))
                    .put("ports", JSONArray(relay.portCandidates(addr))),
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

    private fun buildMetrics(profile: VpnProfile): JSONObject {
        return JSONObject()
            .put("enabled", false)
            .put("window_seconds", profile.metrics.windowSeconds.coerceAtLeast(1))
            .put("file_enabled", false)
            .put("file_dir", "")
    }

    private fun buildSplitTunnel(profile: VpnProfile): JSONObject {
        val enabled = profile.splitTunnelMode != SplitTunnelMode.DISABLED
        return JSONObject()
            .put("enabled", enabled)
            .put("mode", profile.splitTunnelMode.toWireValue())
            .put("apps_win", JSONArray(emptyList<String>()))
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

    private fun SplitTunnelMode.toWireValue(): String = when (this) {
        SplitTunnelMode.DISABLED -> "whitelist"
        SplitTunnelMode.ONLY_SELECTED -> "whitelist"
        SplitTunnelMode.EXCLUDE_SELECTED -> "blacklist"
    }

    private fun VpnRelayTarget.portCandidates(addr: String): List<Int> {
        val explicitPorts = ports
            .filter { it in 1..65535 }
            .distinct()
        if (explicitPorts.isNotEmpty()) {
            return explicitPorts
        }
        return addr.parseRelayPort()?.let(::listOf).orEmpty()
    }

    private fun String.parseRelayPort(): Int? {
        val separatorIndex = lastIndexOf(':')
        if (separatorIndex <= 0 || separatorIndex == lastIndex) {
            return null
        }
        return substring(separatorIndex + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun normalizeServerFailbackDelay(value: Int): Int {
        require(value == -1 || value >= 0) {
            "server_failback_delay_sec must be -1, 0, or a positive integer."
        }
        return if (value == 0) 60 else value
    }

    private companion object {
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
