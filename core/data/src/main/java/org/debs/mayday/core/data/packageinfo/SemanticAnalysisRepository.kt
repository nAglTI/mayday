package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.InstalledApp

interface SemanticAnalysisRepository {
    suspend fun analyzeApp(
        packageName: String,
        force: Boolean = false,
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
