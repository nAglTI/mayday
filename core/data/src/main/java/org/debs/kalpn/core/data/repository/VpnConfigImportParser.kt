package org.debs.kalpn.core.data.repository

import org.debs.kalpn.core.model.SplitTunnelMode
import org.debs.kalpn.core.model.VpnProfile
import org.debs.kalpn.core.model.VpnServerTarget
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConfigImportParser @Inject constructor() {

    fun parse(rawConfig: String, currentProfileName: String = "Imported"): VpnProfile {
        val trimmed = rawConfig.trim()
        require(trimmed.isNotBlank()) { "Config file is empty." }

        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJson(trimmed, currentProfileName)
        } else {
            parseYaml(trimmed, currentProfileName)
        }
    }

    private fun parseJson(rawConfig: String, currentProfileName: String): VpnProfile {
        val json = JSONObject(rawConfig)
        return profileFromMap(
            profileName = currentProfileName,
            relay = json.optString("relay"),
            userId = json.opt("user_id")?.toString().orEmpty(),
            tunName = json.optString("tun_name"),
            dns = json.optString("dns"),
            servers = json.optJSONArray("servers") ?: JSONArray(),
            splitTunnel = json.optJSONObject("split_tunnel"),
        )
    }

    private fun parseYaml(rawConfig: String, currentProfileName: String): VpnProfile {
        val yaml = Yaml().load<Any?>(rawConfig)
        require(yaml is Map<*, *>) { "Unsupported YAML structure." }

        val relay = yaml["relay"]?.toString().orEmpty()
        val userId = yaml["user_id"]?.toString().orEmpty()
        val tunName = yaml["tun_name"]?.toString().orEmpty()
        val dns = yaml["dns"]?.toString().orEmpty()
        val servers = JSONArray()
        (yaml["servers"] as? List<*>)?.forEach { item ->
            if (item is Map<*, *>) {
                servers.put(
                    JSONObject()
                        .put("id", item["id"]?.toString().orEmpty())
                        .put("key", item["key"]?.toString().orEmpty())
                        .put("priority", item["priority"]?.toString()?.toIntOrNull() ?: 1),
                )
            }
        }
        val splitTunnel = (yaml["split_tunnel"] as? Map<*, *>)?.let { split ->
            JSONObject()
                .put("enabled", split["enabled"] as? Boolean ?: false)
                .put("mode", split["mode"]?.toString().orEmpty())
                .put(
                    "apps_android",
                    JSONArray((split["apps_android"] as? List<*>)?.map { it.toString() }.orEmpty()),
                )
        }

        return profileFromMap(
            profileName = currentProfileName,
            relay = relay,
            userId = userId,
            tunName = tunName,
            dns = dns,
            servers = servers,
            splitTunnel = splitTunnel,
        )
    }

    private fun profileFromMap(
        profileName: String,
        relay: String,
        userId: String,
        tunName: String,
        dns: String,
        servers: JSONArray,
        splitTunnel: JSONObject?,
    ): VpnProfile {
        require(relay.isNotBlank()) { "relay is required." }
        require(userId.isNotBlank()) { "user_id is required." }
        require(userId.toLongOrNull() != null) { "user_id must be an integer." }
        val relayHost = relay.substringBefore(':')
        val relayPort = relay.substringAfter(':', "443").toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: 443
        require(relayHost.isNotBlank()) { "relay host is required." }
        val importedServers = buildList {
            for (index in 0 until servers.length()) {
                val item = servers.getJSONObject(index)
                val id = item.optString("id")
                val key = item.optString("key")
                if (id.isNotBlank() && key.isNotBlank()) {
                    add(
                        VpnServerTarget(
                            id = id,
                            key = key,
                            priority = item.optInt("priority", 1),
                        ),
                    )
                }
            }
        }
        require(importedServers.isNotEmpty()) { "servers[] must contain at least one server." }

        val splitMode = when (splitTunnel?.optString("mode").orEmpty()) {
            "whitelist" -> SplitTunnelMode.ONLY_SELECTED
            "blacklist" -> SplitTunnelMode.EXCLUDE_SELECTED
            else -> SplitTunnelMode.DISABLED
        }
        val selectedPackages = buildSet {
            val array = splitTunnel?.optJSONArray("apps_android") ?: JSONArray()
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }.filter(String::isNotBlank).toSet()

        return VpnProfile(
            profileName = profileName,
            relayHost = relayHost,
            relayPort = relayPort,
            userId = userId,
            servers = importedServers,
            tunName = tunName,
            dnsServers = dns.split(',').map(String::trim).filter(String::isNotBlank).ifEmpty {
                listOf("1.1.1.1")
            },
            splitTunnelMode = if (splitTunnel?.optBoolean("enabled", false) == true) {
                splitMode
            } else {
                SplitTunnelMode.DISABLED
            },
            selectedPackages = selectedPackages,
        )
    }
}
