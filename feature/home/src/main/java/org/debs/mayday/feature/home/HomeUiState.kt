package org.debs.mayday.feature.home

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnConnectionStatus

data class HomeUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "",
    val detail: String = "",
    val engineAvailable: Boolean = false,
    val engineDiagnostics: String? = null,
    val profileName: String = "Primary",
    val endpointSummary: String = "Relay not configured",
    val primaryServerId: String = "",
    val userId: String = "",
    val serverCount: Int = 0,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackageCount: Int = 0,
)
