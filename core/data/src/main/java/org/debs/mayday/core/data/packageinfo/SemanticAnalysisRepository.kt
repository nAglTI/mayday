package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.InstalledApp

interface SemanticAnalysisRepository {
    suspend fun analyzeApp(
        packageName: String,
        force: Boolean = false,
        performanceConfig: SemanticAnalyzerPerformanceConfig = SemanticAnalyzerPerformanceConfig.DEFAULT,
    ): AppSemanticAnalysisResult?

    suspend fun exportReport(
        items: List<SemanticAnalysisExportItem>,
        onProgress: (SemanticAnalysisExportProgress) -> Unit = {},
    ): SemanticAnalysisExportResult

    suspend fun cachedAnalysis(
        packageName: String,
    ): AppSemanticAnalysisResult?

    suspend fun apkSizeBytes(
        packageName: String,
    ): Long?
}

enum class SemanticScanPerformanceProfile {
    BALANCED,
    BACKGROUND_GENTLE,
    SPEED_DIAGNOSTIC,
}

data class SemanticAnalyzerPerformanceConfig(
    val profile: SemanticScanPerformanceProfile,
    val maxParallelMethodAnalysisApps: Int,
    val maxHelperPermits: Int,
    val maxHelpersPerApp: Int,
) {
    companion object {
        val BALANCED = SemanticAnalyzerPerformanceConfig(
            profile = SemanticScanPerformanceProfile.BALANCED,
            maxParallelMethodAnalysisApps = 2,
            maxHelperPermits = 2,
            maxHelpersPerApp = 1,
        )

        val BACKGROUND_GENTLE = SemanticAnalyzerPerformanceConfig(
            profile = SemanticScanPerformanceProfile.BACKGROUND_GENTLE,
            maxParallelMethodAnalysisApps = 1,
            maxHelperPermits = 2,
            maxHelpersPerApp = 1,
        )

        val SPEED_DIAGNOSTIC = SemanticAnalyzerPerformanceConfig(
            profile = SemanticScanPerformanceProfile.SPEED_DIAGNOSTIC,
            maxParallelMethodAnalysisApps = 2,
            maxHelperPermits = 4,
            maxHelpersPerApp = 2,
        )

        val DEFAULT = SPEED_DIAGNOSTIC

        fun forProfile(profile: SemanticScanPerformanceProfile): SemanticAnalyzerPerformanceConfig {
            return when (profile) {
                SemanticScanPerformanceProfile.BALANCED -> BALANCED
                SemanticScanPerformanceProfile.BACKGROUND_GENTLE -> BACKGROUND_GENTLE
                SemanticScanPerformanceProfile.SPEED_DIAGNOSTIC -> SPEED_DIAGNOSTIC
            }
        }
    }
}

data class SemanticAnalysisExportItem(
    val app: InstalledApp,
    val analysis: AppSemanticAnalysisResult,
)

data class SemanticAnalysisExportResult(
    val fileName: String,
    val absolutePath: String,
    val exportedApps: Int,
    val mimeType: String,
)

data class SemanticAnalysisExportProgress(
    val stage: SemanticAnalysisExportStage,
    val currentFileName: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

enum class SemanticAnalysisExportStage {
    PREPARING,
    WRITING_REPORT,
    COPYING_ARTIFACTS,
    FINALIZING,
}
