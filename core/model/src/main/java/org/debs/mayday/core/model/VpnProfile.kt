package org.debs.mayday.core.model

data class VpnProfile(
    val profileName: String = "Primary",
    val relays: List<VpnRelayTarget> = emptyList(),
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
        val primaryRelay = relays.firstOrNull() ?: return "Relay not configured"
        if (primaryRelay.addr.isBlank()) {
            return "Relay not configured"
        }

        return buildString {
            append(primaryRelay.addr)
            if (relays.size > 1) {
                append(" (")
                append(relays.size)
                append(" relays)")
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
