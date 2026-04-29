package org.debs.mayday.feature.split

import org.debs.mayday.core.model.SplitTunnelMode

sealed interface SplitUiEvent {
    data object BackClicked : SplitUiEvent
    data object RefreshRequested : SplitUiEvent
    data object SaveClicked : SplitUiEvent
    data object MessageShown : SplitUiEvent
    data class ModeChanged(val value: SplitTunnelMode) : SplitUiEvent
    data class ShowSystemAppsChanged(val value: Boolean) : SplitUiEvent
    data class SearchQueryChanged(val value: String) : SplitUiEvent
    data class PackageSelectionChanged(
        val packageName: String,
        val selected: Boolean,
    ) : SplitUiEvent
}

sealed interface SplitUiEffect {
    data object NavigateBack : SplitUiEffect
}
