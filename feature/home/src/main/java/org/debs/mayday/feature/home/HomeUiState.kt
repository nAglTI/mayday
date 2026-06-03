package org.debs.mayday.feature.home

import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.AppUpdateInfo
import org.debs.mayday.core.model.VpnProfileCompatibilityIssue
import org.debs.mayday.core.model.VpnConnectionStatus

data class HomeUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "",
    val detail: String = "",
    val engineAvailable: Boolean = false,
    val engineDiagnostics: String? = null,
    val coreState: String = "",
    val vpnState: String = "",
    val activeRelayId: String = "",
    val activeTransportId: String = "",
    val activeTransportLabel: String = "",
    val activeServerId: String = "",
    val uploadBps: Double = 0.0,
    val downloadBps: Double = 0.0,
    val aggregateBps: Double = 0.0,
    val protocolDiagnostics: List<String> = emptyList(),
    val endpointDiagnostics: List<String> = emptyList(),
    val endpointSummary: String = "",
    val primaryServerId: String = "",
    val userId: String = "",
    val serverCount: Int = 0,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackageCount: Int = 0,
    val availableUpdate: AppUpdateInfo? = null,
    val profileCompatibilityIssue: VpnProfileCompatibilityIssue? = null,
)
