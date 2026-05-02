package org.debs.mayday.core.model

enum class AppSemanticSignalType {
    CFG,
    DFG,
    CALL_GRAPH,
    STRING_FLOW,
    COMBINATION,
}

enum class AppSemanticRiskScope {
    APP_CODE,
    SDK_CODE,
    NATIVE_CODE,
    MANIFEST,
    CROSS_LAYER,
}

enum class AppSemanticEvidenceSource {
    DIRECT_APP_CODE,
    SDK,
    NATIVE,
    MANIFEST_ONLY,
    APP_TO_SDK,
}

data class AppSemanticSignal(
    val type: AppSemanticSignalType,
    val title: String,
    val description: String,
    val evidence: String,
    val confidence: Int,
    val scope: AppSemanticRiskScope = AppSemanticRiskScope.APP_CODE,
    val source: AppSemanticEvidenceSource = AppSemanticEvidenceSource.DIRECT_APP_CODE,
    val evidenceChain: List<String> = listOf(evidence).filter(String::isNotBlank),
)

data class AppSemanticRiskBucket(
    val score: Int = 0,
    val riskLevel: AppRiskLevel = AppRiskLevel.CLEAN,
    val signals: List<AppSemanticSignal> = emptyList(),
)

data class AppSemanticAnalysisResult(
    val score: Int = 0,
    val riskLevel: AppRiskLevel = AppRiskLevel.CLEAN,
    val signals: List<AppSemanticSignal> = emptyList(),
    val appCodeRisk: AppSemanticRiskBucket = AppSemanticRiskBucket(),
    val sdkCodeRisk: AppSemanticRiskBucket = AppSemanticRiskBucket(),
    val nativeCodeRisk: AppSemanticRiskBucket = AppSemanticRiskBucket(),
    val manifestRisk: AppSemanticRiskBucket = AppSemanticRiskBucket(),
    val crossLayerRisk: AppSemanticRiskBucket = AppSemanticRiskBucket(),
    val methodsAnalyzed: Int = 0,
    val cfgNodeCount: Int = 0,
    val cfgEdgeCount: Int = 0,
    val dfgEdgeCount: Int = 0,
    val scannedAtEpochMillis: Long = 0L,
) {
    val hasWarnings: Boolean
        get() = riskLevel != AppRiskLevel.CLEAN
}
