package org.debs.mayday.core.gomobile.bridge

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnCoreConfigEncoder @Inject constructor() {

    fun encode(profile: VpnProfile): String {
        val relayHost = profile.relayHost.trim()
        val userId = profile.userId.trim().toLongOrNull()

        require(relayHost.isNotBlank()) { "Relay host is required." }
        require(userId != null && userId >= 0) { "User ID must be a non-negative integer." }
        require(profile.servers.isNotEmpty()) { "At least one server is required." }

        val root = JSONObject()
            .put("relay", "$relayHost:${profile.relayPort}")
            .put("user_id", userId)
            .put("dns", profile.dnsServers.firstOrNull().orEmpty().ifBlank { "1.1.1.1" })
            .put("servers", buildServersArray(profile))

        if (profile.tunName.isNotBlank()) {
            root.put("tun_name", profile.tunName.trim())
        }

        buildSplitTunnel(profile)?.let { splitTunnel ->
            root.put("split_tunnel", splitTunnel)
        }

        return root.toString()
    }

    private fun buildServersArray(profile: VpnProfile): JSONArray {
        val array = JSONArray()
        profile.servers.forEach { server ->
            require(server.id.isNotBlank()) { "Server ID is required." }
            require(server.key.isNotBlank()) { "Server key is required." }
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("key", server.key)
                    .put("priority", server.priority.coerceAtLeast(1)),
            )
        }
        return array
    }

    private fun buildSplitTunnel(profile: VpnProfile): JSONObject? {
        if (profile.splitTunnelMode == SplitTunnelMode.DISABLED) {
            return null
        }

        return JSONObject()
            .put("enabled", true)
            .put("mode", profile.splitTunnelMode.toWireValue())
            .put("apps_android", JSONArray(profile.selectedPackages.sorted()))
    }

    private fun SplitTunnelMode.toWireValue(): String = when (this) {
        SplitTunnelMode.DISABLED -> "whitelist"
        SplitTunnelMode.ONLY_SELECTED -> "whitelist"
        SplitTunnelMode.EXCLUDE_SELECTED -> "blacklist"
    }
}
