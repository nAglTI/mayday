package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
import org.debs.mayday.core.model.VpnRelayTarget
import org.debs.mayday.core.model.VpnServerTarget
import org.debs.mayday.core.model.VpnTransportMode
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnConfigImportParser @Inject constructor() {

    fun parse(rawConfig: String, currentProfileName: String = "Imported"): VpnProfile {
        val trimmed = rawConfig.decodeMaydayImportKey().trim()
        require(trimmed.isNotBlank()) { "Import key is empty." }
        return parseDecodedConfig(trimmed, currentProfileName).also { profile ->
            require(VpnProfileCompatibilityValidator.firstIssue(profile) == null) {
                "Import key is not compatible with the current VPN core. Please get a new key."
            }
        }
    }

    private fun String.decodeMaydayImportKey(): String {
        val rawInput = trim()
        val payload = rawInput.extractMaydayImportPayload() ?: rawInput
        val compactPayload = payload.percentDecode().filterNot { it.isWhitespace() }
        require(compactPayload.isNotBlank()) { "Import key is empty." }
        require(BASE64_PAYLOAD_PATTERN.matches(compactPayload)) {
            "Import key must be a Base64 Mayday import key."
        }

        val paddedPayload = compactPayload.withBase64Padding()
        var decodedBytes: ByteArray? = null
        for (decoder in BASE64_DECODERS) {
            decodedBytes = runCatching { decoder.decode(paddedPayload) }.getOrNull()
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

                val name = parameter.substring(0, separatorIndex).percentDecode()
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

    private fun String.percentDecode(): String {
        if (!contains('%')) {
            return this
        }

        val result = StringBuilder(length)
        var index = 0
        while (index < length) {
            val char = this[index]
            if (char == '%' && index + 2 < length) {
                val hexValue = substring(index + 1, index + 3).toIntOrNull(radix = 16)
                if (hexValue != null) {
                    result.append(hexValue.toChar())
                    index += 3
                    continue
                }
            }
            result.append(char)
            index += 1
        }
        return result.toString()
    }

    private fun String.withBase64Padding(): String {
        val missingPadding = (BASE64_GROUP_SIZE - length % BASE64_GROUP_SIZE) % BASE64_GROUP_SIZE
        return if (missingPadding == 0) this else this + "=".repeat(missingPadding)
    }

    private fun parseDecodedConfig(rawConfig: String, currentProfileName: String): VpnProfile {
        return if (rawConfig.startsWith("{")) {
            parseJson(rawConfig, currentProfileName)
        } else {
            parseYaml(rawConfig, currentProfileName)
        }
    }

    private fun parseJson(rawConfig: String, currentProfileName: String): VpnProfile {
        val json = JSONObject(rawConfig)
        requireSupportedConfigVersion(json.opt("config_version"))
        val transportMode = parseTransportMode(json.optJSONObject("transport")?.opt("mode"))
        return profileFromMap(
            profileName = currentProfileName,
            relays = json.optJSONArray("discovery_relays"),
            userId = json.opt("user_id")?.toString().orEmpty(),
            tunName = json.optString("tun_name"),
            dns = json.optString("dns"),
            serverFailbackDelaySec = normalizeServerFailbackDelay(
                json.opt("server_failback_delay_sec"),
            ),
            tunnelMtu = normalizeTunnelMtu(
                rawMtu = json.opt("tunnel_mtu"),
                transportMode = transportMode,
                disableIpv6 = json.opt("disable_ipv6").toBoolean(default = false),
            ),
            transportMode = transportMode,
            prestartFullProbe = json.opt("prestart_full_probe").toBoolean(default = false),
            steadyStateQuickProbeEnabled = json.opt("steady_state_quick_probe_enabled").toBoolean(default = false),
            steadyStateBenchmarkEnabled = json.opt("steady_state_benchmark_enabled").toBoolean(default = false),
            networkRescueProfile = parseNetworkRescue(json.optJSONObject("network_rescue")),
            disableIpv6 = json.opt("disable_ipv6").toBoolean(default = false),
            packetFragmentPayloadBytes = normalizePacketFragmentPayloadBytes(
                json.opt("packet_fragment_payload_bytes"),
            ),
            disablePacketBatching = json.opt("disable_packet_batching").toBoolean(default = false),
            packetPaddingMinBytes = normalizePacketPaddingBytes(json.opt("packet_padding_min_bytes")),
            packetPaddingMaxBytes = normalizePacketPaddingBytes(json.opt("packet_padding_max_bytes")),
            metrics = parseMetrics(json.optJSONObject("metrics")),
            servers = json.optJSONArray("servers") ?: JSONArray(),
            splitTunnel = json.optJSONObject("split_tunnel"),
            preservedConfigJson = json.toString(),
        )
    }

    private fun parseYaml(rawConfig: String, currentProfileName: String): VpnProfile {
        val yaml = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(rawConfig)
        require(yaml is Map<*, *>) { "Import key must decode to a YAML or JSON config." }
        requireSupportedConfigVersion(yaml["config_version"])
        val preservedConfigJson = yaml.toJsonObject().toString()

        val relays = JSONArray()
        (yaml["discovery_relays"] as? List<*>)?.forEachIndexed { index, item ->
            if (item is Map<*, *>) {
                relays.put(
                    JSONObject()
                        .put("id", item["id"]?.toString().orEmpty().ifBlank { "relay-${index + 1}" })
                        .put("addr", item["addr"]?.toString().orEmpty())
                        .put("short_id", item["short_id"].toIntOrNull(default = index + 1))
                        .put("relay_key", item["relay_key"]?.toString().orEmpty())
                        .put(
                            "transport_ports",
                            (item["transport_ports"] as? Map<*, *>)?.toJsonObject() ?: JSONObject(),
                        )
                        .put(
                            "endpoint_addrs",
                            JSONArray(
                                (item["endpoint_addrs"] as? List<*>)
                                    ?.map { it.toString().trim() }
                                    ?.filter(String::isNotBlank)
                                    .orEmpty(),
                            ),
                        )
                )
            }
        }

        val transport = yaml["transport"] as? Map<*, *>
        val servers = JSONArray()
        (yaml["servers"] as? List<*>)?.forEach { item ->
            if (item is Map<*, *>) {
                servers.put(
                    JSONObject()
                        .put("id", item["id"]?.toString().orEmpty())
                        .put("key", item["key"]?.toString().orEmpty())
                        .put("priority", item["priority"].toIntOrNull(default = 1)),
                )
            }
        }
        val splitTunnel = (yaml["split_tunnel"] as? Map<*, *>)?.let { split ->
            JSONObject()
                .put("enabled", split["enabled"].toBoolean(default = false))
                .put("apps_mode", split["apps_mode"]?.toString() ?: split["mode"]?.toString().orEmpty())
                .put(
                    "apps_android",
                    JSONArray((split["apps_android"] as? List<*>)?.map { it.toString() }.orEmpty()),
                )
        }
        val transportMode = parseTransportMode(transport?.get("mode"))
        val disableIpv6 = yaml["disable_ipv6"].toBoolean(default = false)

        return profileFromMap(
            profileName = currentProfileName,
            relays = relays,
            userId = yaml["user_id"]?.toString().orEmpty(),
            tunName = yaml["tun_name"]?.toString().orEmpty(),
            dns = yaml["dns"]?.toString().orEmpty(),
            serverFailbackDelaySec = normalizeServerFailbackDelay(yaml["server_failback_delay_sec"]),
            tunnelMtu = normalizeTunnelMtu(
                rawMtu = yaml["tunnel_mtu"],
                transportMode = transportMode,
                disableIpv6 = disableIpv6,
            ),
            transportMode = transportMode,
            prestartFullProbe = yaml["prestart_full_probe"].toBoolean(default = false),
            steadyStateQuickProbeEnabled = yaml["steady_state_quick_probe_enabled"].toBoolean(default = false),
            steadyStateBenchmarkEnabled = yaml["steady_state_benchmark_enabled"].toBoolean(default = false),
            networkRescueProfile = parseNetworkRescue(yaml["network_rescue"] as? Map<*, *>),
            disableIpv6 = disableIpv6,
            packetFragmentPayloadBytes = normalizePacketFragmentPayloadBytes(
                yaml["packet_fragment_payload_bytes"],
            ),
            disablePacketBatching = yaml["disable_packet_batching"].toBoolean(default = false),
            packetPaddingMinBytes = normalizePacketPaddingBytes(yaml["packet_padding_min_bytes"]),
            packetPaddingMaxBytes = normalizePacketPaddingBytes(yaml["packet_padding_max_bytes"]),
            metrics = parseMetrics(yaml["metrics"] as? Map<*, *>),
            servers = servers,
            splitTunnel = splitTunnel,
            preservedConfigJson = preservedConfigJson,
        )
    }

    private fun profileFromMap(
        profileName: String,
        relays: JSONArray?,
        userId: String,
        tunName: String,
        dns: String,
        serverFailbackDelaySec: Int,
        tunnelMtu: Int,
        transportMode: VpnTransportMode,
        prestartFullProbe: Boolean,
        steadyStateQuickProbeEnabled: Boolean,
        steadyStateBenchmarkEnabled: Boolean,
        networkRescueProfile: NetworkRescueProfile,
        disableIpv6: Boolean,
        packetFragmentPayloadBytes: Int,
        disablePacketBatching: Boolean,
        packetPaddingMinBytes: Int,
        packetPaddingMaxBytes: Int,
        metrics: VpnMetricsConfig,
        servers: JSONArray,
        splitTunnel: JSONObject?,
        preservedConfigJson: String,
    ): VpnProfile {
        require(userId.isNotBlank()) { "user_id is required." }
        require(userId.toLongOrNull()?.let { it > 0 } == true) {
            "user_id must be a positive integer."
        }
        val importedRelays = parseRelays(relays)
        require(importedRelays.isNotEmpty()) { "discovery_relays[] must contain at least one relay." }
        require(importedRelays.map { it.shortId }.distinct().size == importedRelays.size) {
            "discovery_relays[].short_id values must be unique."
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

        val splitMode = when (
            splitTunnel?.optString("apps_mode").orEmpty().ifBlank {
                splitTunnel?.optString("mode").orEmpty()
            }
        ) {
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
            mtu = tunnelMtu,
            serverFailbackDelaySec = serverFailbackDelaySec,
            transportMode = transportMode,
            prestartFullProbe = prestartFullProbe,
            steadyStateQuickProbeEnabled = steadyStateQuickProbeEnabled,
            steadyStateBenchmarkEnabled = steadyStateBenchmarkEnabled,
            networkRescueProfile = networkRescueProfile,
            disableIpv6 = disableIpv6,
            packetFragmentPayloadBytes = packetFragmentPayloadBytes,
            disablePacketBatching = disablePacketBatching,
            packetPaddingMinBytes = packetPaddingMinBytes,
            packetPaddingMaxBytes = packetPaddingMaxBytes,
            metrics = metrics,
            splitTunnelMode = if (splitTunnel?.optBoolean("enabled", false) == true) {
                splitMode
            } else {
                SplitTunnelMode.DISABLED
            },
            selectedPackages = selectedPackages,
            preservedConfigJson = preservedConfigJson,
        )
    }

    private fun parseRelays(relays: JSONArray?): List<VpnRelayTarget> {
        val array = relays ?: JSONArray()
        return buildList {
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
                        relayKey = item.optString("relay_key").trim(),
                        transportPorts = item.optJSONObject("transport_ports").parseTransportPorts(),
                        endpointAddrs = item.optJSONArray("endpoint_addrs").parseStringList(),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.parseStringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }.distinct()
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

    private fun parseNetworkRescue(json: JSONObject?): NetworkRescueProfile {
        if (json == null) {
            return NetworkRescueProfile.OFF
        }
        return normalizeNetworkRescue(
            enabled = json.opt("enabled").toBoolean(default = false),
            profile = json.optString("profile"),
        )
    }

    private fun parseNetworkRescue(map: Map<*, *>?): NetworkRescueProfile {
        if (map == null) {
            return NetworkRescueProfile.OFF
        }
        return normalizeNetworkRescue(
            enabled = map["enabled"].toBoolean(default = false),
            profile = map["profile"]?.toString().orEmpty(),
        )
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

    private fun parseTransportMode(rawMode: Any?): VpnTransportMode {
        val mode = rawMode?.toString().orEmpty().trim().lowercase()
        if (mode.isBlank()) {
            return VpnTransportMode.AUTO
        }
        return VpnTransportMode.fromRuntimeId(mode)
            ?: throw IllegalArgumentException("transport.mode must be auto, tcp, utp, ws, https, rest, udp, or raw-udp.")
    }

    private fun normalizeServerFailbackDelay(rawDelay: Any?): Int {
        val delay = rawDelay.toIntOrNull(default = 60)
        require(delay == -1 || delay >= 0) {
            "server_failback_delay_sec must be -1, 0, or a positive integer."
        }
        return if (delay == 0) 60 else delay
    }

    private fun normalizeTunnelMtu(
        rawMtu: Any?,
        transportMode: VpnTransportMode,
        disableIpv6: Boolean,
    ): Int {
        val mtu = rawMtu.toIntOrNull(default = transportMode.defaultMtu())
        val minMtu = if (disableIpv6) 100 else 1280
        require(mtu in minMtu..1500) {
            "tunnel_mtu must be ${minMtu}..1500 for the current IPv6 mode."
        }
        return mtu
    }

    private fun normalizePacketFragmentPayloadBytes(rawValue: Any?): Int {
        val value = rawValue.toIntOrNull(default = 0)
        require(value == 0 || value in 64..65536) {
            "packet_fragment_payload_bytes must be 0 or a value from 64 to 65536."
        }
        return value
    }

    private fun normalizePacketPaddingBytes(rawValue: Any?): Int {
        val value = rawValue.toIntOrNull(default = 0)
        require(value in 0..1200) {
            "packet padding must be from 0 to 1200 bytes."
        }
        return value
    }

    private fun requireSupportedConfigVersion(rawValue: Any?) {
        if (rawValue == null || rawValue == JSONObject.NULL) {
            return
        }
        val version = when (rawValue) {
            is Number -> rawValue.toInt()
            else -> rawValue.toString().trim().toIntOrNull()
        }
        require(version != null) {
            "config_version must be an integer."
        }
        require(version <= SUPPORTED_CONFIG_VERSION) {
            "Import key is not compatible with this app version. Please update the app or get a new key."
        }
    }

    private fun JSONArray?.parsePorts(): List<Int> {
        if (this == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until length()) {
                val port = opt(index).toIntOrNull(default = -1)
                require(port in 1..65535) {
                    "discovery_relays[].transport_ports must contain valid port numbers."
                }
                add(port)
            }
        }.distinct()
    }

    private fun JSONObject?.parseTransportPorts(): Map<String, List<Int>> {
        if (this == null) {
            return emptyMap()
        }

        return buildMap {
            keys().forEach { key ->
                val protocolId = key.trim().lowercase()
                if (protocolId.isBlank()) {
                    return@forEach
                }
                val ports = optJSONArray(key).parsePorts()
                if (ports.isNotEmpty()) {
                    put(protocolId, ports)
                }
            }
        }
    }

    private fun Any?.toIntOrNull(default: Int): Int {
        return when (this) {
            null, JSONObject.NULL -> default
            is Number -> toInt()
            else -> toString().trim().toIntOrNull() ?: default
        }
    }

    private fun Any?.toBoolean(default: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> toInt() != 0
            is String -> when (trim().lowercase()) {
                "true" -> true
                "1" -> true
                "yes" -> true
                "false" -> false
                "0" -> false
                "no" -> false
                else -> default
            }
            else -> default
        }
    }

    private fun Map<*, *>.toJsonObject(): JSONObject {
        val json = JSONObject()
        forEach { (key, value) ->
            val name = key?.toString() ?: return@forEach
            json.put(name, value.toJsonValue())
        }
        return json
    }

    private fun Any?.toJsonValue(): Any {
        return when (this) {
            null -> JSONObject.NULL
            is Map<*, *> -> toJsonObject()
            is List<*> -> JSONArray().also { array ->
                forEach { item -> array.put(item.toJsonValue()) }
            }
            is Boolean,
            is Number,
            is String,
            -> this
            else -> toString()
        }
    }

    private companion object {
        const val MAYDAY_IMPORT_PATH_PREFIX = "mayday://import/"
        const val MAYDAY_IMPORT_QUERY_PREFIX = "mayday://import?"
        const val BASE64_GROUP_SIZE = 4
        val BASE64_DECODERS = arrayOf(
            Base64.getDecoder(),
            Base64.getUrlDecoder(),
            Base64.getMimeDecoder(),
        )
        val BASE64_PAYLOAD_PATTERN = Regex("^[A-Za-z0-9+/_=-]+$")
        val SERVER_KEY_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        const val SUPPORTED_CONFIG_VERSION = 1
    }
}
