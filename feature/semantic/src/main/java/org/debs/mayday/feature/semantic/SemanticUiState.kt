package org.debs.mayday.feature.semantic

import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.UiPreferences

data class SemanticUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val isLoading: Boolean = true,
    val appSearchQuery: String = "",
    val showSystemApps: Boolean = false,
    val apps: List<SemanticAppItem> = emptyList(),
    val scannedApps: Int = 0,
    val totalApps: Int = 0,
    val isScanRunning: Boolean = false,
    val isScanPaused: Boolean = false,
    val isExportingReport: Boolean = false,
    val selectedPackageNames: Set<String> = emptySet(),
    val queuedPackageNames: Set<String> = emptySet(),
    val scanningPackageNames: Set<String> = emptySet(),
    val currentScanPackageName: String? = null,
    val currentScanLabel: String? = null,
    val exportProgress: SemanticExportUiProgress? = null,
    val detailsPackageName: String? = null,
    val message: String? = null,
)

data class SemanticAppItem(
    val app: InstalledApp,
    val analysis: AppSemanticAnalysisResult = AppSemanticAnalysisResult(),
)

data class SemanticExportUiProgress(
    val stage: SemanticExportUiStage,
    val currentFileName: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (copiedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

enum class SemanticExportUiStage {
    PREPARING,
    WRITING_REPORT,
    COPYING_ARTIFACTS,
    FINALIZING,
}
