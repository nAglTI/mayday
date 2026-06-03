package org.debs.mayday.core.model

object VpnProfileCompatibilityValidator {

    fun firstIssue(profile: VpnProfile): VpnProfileCompatibilityIssue? {
        if (profile.relays.isEmpty()) {
            return VpnProfileCompatibilityIssue(VpnProfileCompatibilityIssueType.MISSING_RELAY)
        }
        if (profile.userId.trim().toLongOrNull()?.let { it > 0 } != true) {
            return VpnProfileCompatibilityIssue(VpnProfileCompatibilityIssueType.INVALID_USER_ID)
        }
        if (profile.servers.isEmpty()) {
            return VpnProfileCompatibilityIssue(VpnProfileCompatibilityIssueType.MISSING_SERVER)
        }
        if (profile.serverFailbackDelaySec != -1 && profile.serverFailbackDelaySec < 0) {
            return VpnProfileCompatibilityIssue(
                VpnProfileCompatibilityIssueType.INVALID_SERVER_FAILBACK_DELAY,
            )
        }
        if (!profile.isTunnelMtuValid()) {
            return VpnProfileCompatibilityIssue(VpnProfileCompatibilityIssueType.INVALID_TUNNEL_MTU)
        }
        if (profile.packetFragmentPayloadBytes != 0 && profile.packetFragmentPayloadBytes !in 64..65536) {
            return VpnProfileCompatibilityIssue(
                VpnProfileCompatibilityIssueType.INVALID_PACKET_FRAGMENT_PAYLOAD,
            )
        }

        val shortIds = mutableSetOf<Int>()
        profile.relays.forEach { relay ->
            if (!relay.addr.trim().isRelayHostOnly()) {
                return VpnProfileCompatibilityIssue(
                    type = VpnProfileCompatibilityIssueType.INVALID_RELAY_ADDRESS,
                    relayId = relay.id,
                )
            }
            if (!relay.hasUsableTransportPorts(profile.transportMode)) {
                return VpnProfileCompatibilityIssue(
                    type = VpnProfileCompatibilityIssueType.MISSING_RELAY_TRANSPORT_PORTS,
                    relayId = relay.id,
                )
            }
            if (!shortIds.add(relay.shortId.coerceAtLeast(1))) {
                return VpnProfileCompatibilityIssue(
                    type = VpnProfileCompatibilityIssueType.DUPLICATE_RELAY_SHORT_ID,
                    relayId = relay.id,
                )
            }
            if (profile.requiresSealedDiscovery() && !HEX_64_PATTERN.matches(relay.relayKey.trim())) {
                return VpnProfileCompatibilityIssue(
                    type = VpnProfileCompatibilityIssueType.MISSING_RELAY_KEY_FOR_CURRENT_CORE,
                    relayId = relay.id,
                )
            }
        }

        profile.servers.forEach { server ->
            if (server.id.trim().isBlank() || !HEX_64_PATTERN.matches(server.key.trim())) {
                return VpnProfileCompatibilityIssue(
                    type = VpnProfileCompatibilityIssueType.INVALID_SERVER_KEY,
                    serverId = server.id,
                )
            }
        }

        return null
    }

    private fun VpnProfile.requiresSealedDiscovery(): Boolean {
        return transportMode == VpnTransportMode.AUTO || transportMode == VpnTransportMode.WS
    }

    private fun VpnProfile.isTunnelMtuValid(): Boolean {
        return if (disableIpv6) {
            mtu in 100..1500
        } else {
            mtu in 1280..1500
        }
    }

    private fun VpnRelayTarget.hasUsableTransportPorts(transportMode: VpnTransportMode): Boolean {
        val validTransportPorts = transportPorts
            .filterKeys(String::isNotBlank)
            .mapValues { (_, ports) -> ports.filter { it in 1..65535 }.distinct() }
            .filterValues(List<Int>::isNotEmpty)
        return when (transportMode) {
            VpnTransportMode.AUTO -> validTransportPorts.isNotEmpty()
            else -> validTransportPorts[transportMode.runtimeId].orEmpty().isNotEmpty()
        }
    }

    private fun String.isRelayHostOnly(): Boolean {
        return isNotBlank() && !contains(':')
    }

    private val HEX_64_PATTERN = Regex("^[0-9a-fA-F]{64}$")
}

data class VpnProfileCompatibilityIssue(
    val type: VpnProfileCompatibilityIssueType,
    val relayId: String? = null,
    val serverId: String? = null,
)

enum class VpnProfileCompatibilityIssueType {
    MISSING_RELAY,
    INVALID_USER_ID,
    MISSING_SERVER,
    INVALID_RELAY_ADDRESS,
    DUPLICATE_RELAY_SHORT_ID,
    MISSING_RELAY_KEY_FOR_CURRENT_CORE,
    INVALID_SERVER_KEY,
    INVALID_SERVER_FAILBACK_DELAY,
    INVALID_TUNNEL_MTU,
    INVALID_PACKET_FRAGMENT_PAYLOAD,
    MISSING_RELAY_TRANSPORT_PORTS,
}
