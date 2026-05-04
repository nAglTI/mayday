package org.debs.mayday.feature.semantic

sealed interface SemanticUiEvent {
    data object BackClicked : SemanticUiEvent
    data object RefreshRequested : SemanticUiEvent
    data object RestartScanClicked : SemanticUiEvent
    data object ScanAllClicked : SemanticUiEvent
    data object ScanSelectedClicked : SemanticUiEvent
    data object SelectVisibleAppsClicked : SemanticUiEvent
    data object ClearSelectionClicked : SemanticUiEvent
    data object PauseScanClicked : SemanticUiEvent
    data object ResumeScanClicked : SemanticUiEvent
    data object ExportReportClicked : SemanticUiEvent
    data object CancelExportClicked : SemanticUiEvent
    data object MessageShown : SemanticUiEvent
    data class ShowSystemAppsChanged(val value: Boolean) : SemanticUiEvent
    data class SearchQueryChanged(val value: String) : SemanticUiEvent
    data class AppSelectionChanged(val packageName: String, val selected: Boolean) : SemanticUiEvent
    data class ScanAppClicked(val packageName: String) : SemanticUiEvent
    data class DetailsClicked(val packageName: String) : SemanticUiEvent
    data object DetailsDismissed : SemanticUiEvent
}

sealed interface SemanticUiEffect {
    data object NavigateBack : SemanticUiEffect

    data class ShareSemanticReport(
        val absolutePath: String,
        val fileName: String,
        val mimeType: String,
    ) : SemanticUiEffect
}
