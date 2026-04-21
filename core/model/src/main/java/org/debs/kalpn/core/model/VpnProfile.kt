package org.debs.kalpn.core.model

data class VpnProfile(
    val profileName: String = "Primary",
    val relayHost: String = "",
    val relayPort: Int = 443,
    val userId: String = "",
    val servers: List<VpnServerTarget> = emptyList(),
    val tunName: String = "",
    val dnsServers: List<String> = listOf("1.1.1.1"),
    val mtu: Int = 1420,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackages: Set<String> = emptySet(),
    val isAutoReconnectEnabled: Boolean = true,
) {
    fun endpointSummary(): String {
        if (relayHost.isBlank()) {
            return "Relay not configured"
        }
        return buildString {
            append(relayHost)
            append(':')
            append(relayPort)
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
