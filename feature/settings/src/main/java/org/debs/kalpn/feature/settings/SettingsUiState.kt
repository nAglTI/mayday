package org.debs.kalpn.feature.settings

import org.debs.kalpn.core.model.InstalledApp
import org.debs.kalpn.core.model.SplitTunnelMode

data class SettingsUiState(
    val profileName: String = "Primary",
    val relayHost: String = "",
    val relayPort: String = "443",
    val userId: String = "",
    val servers: List<ServerDraft> = listOf(ServerDraft()),
    val tunName: String = "",
    val dnsServers: String = "1.1.1.1",
    val mtu: String = "1420",
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val autoReconnect: Boolean = true,
    val installedApps: List<InstalledApp> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val appSearchQuery: String = "",
    val isLoading: Boolean = true,
    val importedConfigName: String? = null,
    val message: String? = null,
)
