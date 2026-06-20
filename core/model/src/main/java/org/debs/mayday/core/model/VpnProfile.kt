package org.debs.mayday.core.model

data class VpnProfile(
    val profileName: String = "Primary",
    val relays: List<VpnRelayTarget> = emptyList(),
    val userId: String = "",
    val servers: List<VpnServerTarget> = emptyList(),
    val tunName: String = "",
    val dnsServers: List<String> = listOf("1.1.1.1"),
    val mtu: Int = VpnTransportMode.AUTO.defaultMtu(),
    val serverFailbackDelaySec: Int = 60,
    val transportMode: VpnTransportMode = VpnTransportMode.AUTO,
    val prestartFullProbe: Boolean = false,
    val steadyStateQuickProbeEnabled: Boolean = false,
    val steadyStateBenchmarkEnabled: Boolean = false,
    val networkRescueProfile: NetworkRescueProfile = NetworkRescueProfile.OFF,
    val disableIpv6: Boolean = false,
    val packetFragmentPayloadBytes: Int = 0,
    val disablePacketBatching: Boolean = false,
    val packetPaddingMinBytes: Int = 0,
    val packetPaddingMaxBytes: Int = 0,
    val metrics: VpnMetricsConfig = VpnMetricsConfig(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackages: Set<String> = emptySet(),
    val isAutoReconnectEnabled: Boolean = true,
    val preservedConfigJson: String = "",
) {
    fun endpointSummary(): String {
        if (relays.isEmpty()) {
            return ""
        }

        return buildString {
            append(relays.size)
            append(" relay")
            if (relays.size != 1) {
                append('s')
            }
            if (servers.isNotEmpty()) {
                append(" -> ")
                append(servers.first().id)
                append(" (")
                append(servers.size)
                append(" server")
                if (servers.size != 1) {
                    append('s')
                }
                append(')')
            }
        }
    }
}
