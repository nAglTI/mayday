package org.debs.mayday.feature.split

import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences

data class SplitUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val installedApps: List<InstalledApp> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val appSearchQuery: String = "",
    val riskDetailsPackageName: String? = null,
    val isRiskScanRunning: Boolean = false,
    val scannedRiskApps: Int = 0,
    val totalRiskApps: Int = 0,
    val scanningRiskPackageNames: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val message: String? = null,
)
