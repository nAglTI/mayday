package org.debs.mayday.feature.split

import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences

data class SplitUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val installedApps: List<SplitAppItem> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val appSearchQuery: String = "",
    val appSortMode: SplitAppSortMode = SplitAppSortMode.ROUTING,
    val isLoading: Boolean = true,
    val message: String? = null,
)

data class SplitAppItem(
    val app: InstalledApp,
    val semanticAnalysis: AppSemanticAnalysisResult = AppSemanticAnalysisResult(),
)

enum class SplitAppSortMode {
    ROUTING,
    RISK_SCORE,
}
