package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
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
            relays = json.optJSONArray("relays"),
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

        val relays = JSONArray()
        (yaml["relays"] as? List<*>)?.forEachIndexed { index, item ->
            if (item is Map<*, *>) {
                relays.put(
                    JSONObject()
                        .put("id", item["id"]?.toString().orEmpty().ifBlank { "relay-${index + 1}" })
                        .put("addr", item["addr"]?.toString().orEmpty())
                        .put("short_id", item["short_id"]?.toString()?.toIntOrNull() ?: (index + 1)),
                )
            }
        }
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
            relays = relays,
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
        relays: JSONArray?,
        relay: String,
        userId: String,
        tunName: String,
        dns: String,
        servers: JSONArray,
        splitTunnel: JSONObject?,
    ): VpnProfile {
        require(userId.isNotBlank()) { "user_id is required." }
        require(userId.toLongOrNull() != null) { "user_id must be an integer." }
        val importedRelays = parseRelays(relays, relay)
        require(importedRelays.isNotEmpty()) { "relays[] must contain at least one relay." }
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
            relays = importedRelays,
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

    private fun parseRelays(relays: JSONArray?, legacyRelay: String): List<VpnRelayTarget> {
        val importedRelays = buildList {
            val array = relays ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
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
        if (importedRelays.isNotEmpty()) {
            return importedRelays
        }

        val normalizedRelay = legacyRelay.trim()
        if (normalizedRelay.isBlank()) {
            return emptyList()
        }

        return listOf(
            VpnRelayTarget(
                id = "relay-1",
                addr = normalizedRelay,
                shortId = 1,
            ),
        )
    }
}
