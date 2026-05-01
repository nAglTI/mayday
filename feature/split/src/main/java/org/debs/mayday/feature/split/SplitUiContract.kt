package org.debs.mayday.feature.split

import org.debs.mayday.core.model.SplitTunnelMode

sealed interface SplitUiEvent {
    data object BackClicked : SplitUiEvent
    data object RefreshRequested : SplitUiEvent
    data object RestartRiskScanClicked : SplitUiEvent
    data object SaveClicked : SplitUiEvent
    data object MessageShown : SplitUiEvent
    data class ModeChanged(val value: SplitTunnelMode) : SplitUiEvent
    data class ShowSystemAppsChanged(val value: Boolean) : SplitUiEvent
    data class SearchQueryChanged(val value: String) : SplitUiEvent
    data class PackageSelectionChanged(
        val packageName: String,
        val selected: Boolean,
    ) : SplitUiEvent
    data class RiskDetailsClicked(val packageName: String) : SplitUiEvent
    data object RiskDetailsDismissed : SplitUiEvent
    data class OpenAppSettingsClicked(val packageName: String) : SplitUiEvent
    data class OpenAppPermissionsClicked(val packageName: String) : SplitUiEvent
    data class UninstallAppClicked(val packageName: String) : SplitUiEvent
    data class HideRiskWarningClicked(val packageName: String) : SplitUiEvent
}

sealed interface SplitUiEffect {
    data object NavigateBack : SplitUiEffect
    data class OpenAppSettings(val packageName: String) : SplitUiEffect
    data class OpenAppPermissions(val packageName: String) : SplitUiEffect
    data class RequestAppUninstall(val packageName: String) : SplitUiEffect
}
