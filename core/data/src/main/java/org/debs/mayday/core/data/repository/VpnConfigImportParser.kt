package org.debs.mayday.core.data.repository

import android.net.Uri
import android.util.Base64
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConfigImportParser @Inject constructor() {

    fun parse(rawConfig: String, currentProfileName: String = "Imported"): VpnProfile {
        val trimmed = rawConfig.decodeMaydayImportConfig().trim()
        require(trimmed.isNotBlank()) { "Config file is empty." }

        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJson(trimmed, currentProfileName)
        } else {
            parseYaml(trimmed, currentProfileName)
        }
    }

    private fun String.decodeMaydayImportConfig(): String {
        val payload = trim().extractMaydayImportPayload() ?: return trim()
        val compactPayload = Uri.decode(payload).filterNot { it.isWhitespace() }
        require(compactPayload.isNotBlank()) { "Import key is empty." }

        val paddedPayload = compactPayload.withBase64Padding()
        var decodedBytes: ByteArray? = null
        for (flags in BASE64_FLAGS) {
            decodedBytes = runCatching { Base64.decode(paddedPayload, flags) }.getOrNull()
            if (decodedBytes != null) {
                break
            }
        }

        return (decodedBytes ?: throw IllegalArgumentException("Import key must contain valid Base64 config."))
            .toString(Charsets.UTF_8)
    }

    private fun String.extractMaydayImportPayload(): String? {
        if (startsWith(MAYDAY_IMPORT_PATH_PREFIX, ignoreCase = true)) {
            val payload = substring(MAYDAY_IMPORT_PATH_PREFIX.length)
                .substringBefore('#')
                .substringBefore('?')
                .trim()
            require(payload.isNotBlank()) { "Import key is empty." }
            return payload
        }

        if (startsWith(MAYDAY_IMPORT_QUERY_PREFIX, ignoreCase = true)) {
            val query = substringAfter('?', missingDelimiterValue = "")
            var payload: String? = null
            for (parameter in query.split('&')) {
                val separatorIndex = parameter.indexOf('=')
                if (separatorIndex <= 0) {
                    continue
                }

                val name = Uri.decode(parameter.substring(0, separatorIndex))
                if (
                    name.equals("config", ignoreCase = true) ||
                    name.equals("payload", ignoreCase = true)
                ) {
                    payload = parameter.substring(separatorIndex + 1)
                    break
                }
            }
            payload = payload
                ?.substringBefore('#')
                ?.trim()
            require(!payload.isNullOrBlank()) { "Import key is empty." }
            return payload
        }

        return null
    }

    private fun String.withBase64Padding(): String {
        val missingPadding = (BASE64_GROUP_SIZE - length % BASE64_GROUP_SIZE) % BASE64_GROUP_SIZE
        return if (missingPadding == 0) this else this + "=".repeat(missingPadding)
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
            serverFailbackDelaySec = normalizeServerFailbackDelay(
                json.opt("server_failback_delay_sec"),
            ),
            transportMode = parseTransportMode(json.optJSONObject("transport")?.opt("mode")),
            metrics = parseMetrics(json.optJSONObject("metrics")),
            servers = json.optJSONArray("servers") ?: JSONArray(),
            splitTunnel = json.optJSONObject("split_tunnel"),
        )
    }

    private fun parseYaml(rawConfig: String, currentProfileName: String): VpnProfile {
        val yaml = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(rawConfig)
        require(yaml is Map<*, *>) { "Unsupported YAML structure." }

        val relays = JSONArray()
        (yaml["relays"] as? List<*>)?.forEachIndexed { index, item ->
            if (item is Map<*, *>) {
                relays.put(
                    JSONObject()
                        .put("id", item["id"]?.toString().orEmpty().ifBlank { "relay-${index + 1}" })
                        .put("addr", item["addr"]?.toString().orEmpty())
                        .put("short_id", item["short_id"]?.toString()?.toIntOrNull() ?: (index + 1))
                        .put("ports", (item["ports"] as? List<*>).toJsonArray()),
                )
            }
        }
        val relay = yaml["relay"]?.toString().orEmpty()
        val userId = yaml["user_id"]?.toString().orEmpty()
        val tunName = yaml["tun_name"]?.toString().orEmpty()
        val dns = yaml["dns"]?.toString().orEmpty()
        val transport = yaml["transport"] as? Map<*, *>
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
                .put("enabled", split["enabled"].toBoolean(default = false))
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
            serverFailbackDelaySec = normalizeServerFailbackDelay(yaml["server_failback_delay_sec"]),
            transportMode = parseTransportMode(transport?.get("mode")),
            metrics = parseMetrics(yaml["metrics"] as? Map<*, *>),
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
        serverFailbackDelaySec: Int,
        transportMode: VpnTransportMode,
        metrics: VpnMetricsConfig,
        servers: JSONArray,
        splitTunnel: JSONObject?,
    ): VpnProfile {
        require(userId.isNotBlank()) { "user_id is required." }
        require(userId.toLongOrNull()?.let { it > 0 } == true) {
            "user_id must be a positive integer."
        }
        val importedRelays = parseRelays(relays, relay)
        require(importedRelays.isNotEmpty()) { "relays[] must contain at least one relay." }
        require(importedRelays.map { it.shortId }.distinct().size == importedRelays.size) {
            "relays[].short_id values must be unique."
        }
        val importedServers = buildList {
            for (index in 0 until servers.length()) {
                val item = servers.getJSONObject(index)
                val id = item.optString("id").trim()
                val key = item.optString("key").trim()
                if (id.isBlank() || key.isBlank()) {
                    continue
                }
                require(SERVER_KEY_PATTERN.matches(key)) {
                    "servers[].key must be a 64-character hex string."
                }
                add(
                    VpnServerTarget(
                        id = id,
                        key = key,
                        priority = item.optInt("priority", 1),
                    ),
                )
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
            mtu = transportMode.defaultMtu(),
            serverFailbackDelaySec = serverFailbackDelaySec,
            transportMode = transportMode,
            metrics = metrics,
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
                        ports = item.optJSONArray("ports").parsePorts().ifEmpty {
                            addr.parseRelayPort()?.let(::listOf).orEmpty()
                        },
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
                ports = normalizedRelay.parseRelayPort()?.let(::listOf).orEmpty(),
            ),
        )
    }

    private fun parseMetrics(json: JSONObject?): VpnMetricsConfig {
        if (json == null) {
            return VpnMetricsConfig()
        }
        return VpnMetricsConfig(
            enabled = false,
            windowSeconds = json.optInt("window_seconds", 600).coerceAtLeast(1),
            fileEnabled = false,
            fileDir = "",
        )
    }

    private fun parseMetrics(map: Map<*, *>?): VpnMetricsConfig {
        if (map == null) {
            return VpnMetricsConfig()
        }
        return VpnMetricsConfig(
            enabled = false,
            windowSeconds = map["window_seconds"].toIntOrNull(default = 600).coerceAtLeast(1),
            fileEnabled = false,
            fileDir = "",
        )
    }

    private fun parseTransportMode(rawMode: Any?): VpnTransportMode {
        val mode = rawMode?.toString().orEmpty().trim().lowercase()
        if (mode.isBlank()) {
            return VpnTransportMode.AUTO
        }
        return VpnTransportMode.entries.firstOrNull { it.wireValue == mode }
            ?: throw IllegalArgumentException("transport.mode must be auto, tcp, or utp.")
    }

    private fun normalizeServerFailbackDelay(rawDelay: Any?): Int {
        val delay = rawDelay.toIntOrNull(default = 60)
        require(delay == -1 || delay >= 0) {
            "server_failback_delay_sec must be -1, 0, or a positive integer."
        }
        return if (delay == 0) 60 else delay
    }

    private fun List<*>?.toJsonArray(): JSONArray {
        val array = JSONArray()
        this?.forEach { array.put(it) }
        return array
    }

    private fun JSONArray?.parsePorts(): List<Int> {
        if (this == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until length()) {
                val port = opt(index).toIntOrNull(default = -1)
                require(port in 1..65535) {
                    "relays[].ports must contain valid port numbers."
                }
                add(port)
            }
        }.distinct()
    }

    private fun String.parseRelayPort(): Int? {
        val separatorIndex = lastIndexOf(':')
        if (separatorIndex <= 0 || separatorIndex == lastIndex) {
            return null
        }
        return substring(separatorIndex + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun Any?.toBoolean(default: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is String -> when (trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> default
            }
            else -> default
        }
    }

    private fun Any?.toIntOrNull(default: Int): Int {
        return when (this) {
            null, JSONObject.NULL -> default
            is Number -> toInt()
            else -> toString().trim().toIntOrNull() ?: default
        }
    }

    private companion object {
        const val MAYDAY_IMPORT_PATH_PREFIX = "mayday://import/"
        const val MAYDAY_IMPORT_QUERY_PREFIX = "mayday://import?"
        const val BASE64_GROUP_SIZE = 4
        val BASE64_FLAGS = intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
