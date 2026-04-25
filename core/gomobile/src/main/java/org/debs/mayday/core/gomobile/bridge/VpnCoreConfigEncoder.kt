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
        val userId = profile.userId.trim().toLongOrNull()

        require(profile.relays.isNotEmpty()) { "At least one relay is required." }
        require(userId != null && userId >= 0) { "User ID must be a non-negative integer." }
        require(profile.servers.isNotEmpty()) { "At least one server is required." }

        val root = JSONObject()
            .put("relays", buildRelaysArray(profile))
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

    private fun buildRelaysArray(profile: VpnProfile): JSONArray {
        val array = JSONArray()
        profile.relays.forEachIndexed { index, relay ->
            val addr = relay.addr.trim()
            require(addr.isNotBlank()) { "Relay address is required." }
            array.put(
                JSONObject()
                    .put("id", relay.id.trim().ifBlank { "relay-${index + 1}" })
                    .put("addr", addr)
                    .put("short_id", relay.shortId.coerceAtLeast(1)),
            )
        }
        return array
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
