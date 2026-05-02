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

enum class AppSemanticProofLevel {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        fun from(confidence: Int): AppSemanticProofLevel {
            return when {
                confidence >= 80 -> HIGH
                confidence >= 50 -> MEDIUM
                else -> LOW
            }
        }
    }
}

enum class AppSemanticVerdictStatus {
    PROVEN_CLEAN,
    PROVEN_LOW_RISK,
    UNPROVEN_THREAT,
    PARTIAL_THREAT,
    PROVEN_THREAT,
    INCONSISTENT,
}

object AppSemanticVerdictConfidence {
    fun from(
        score: Int,
        riskLevel: AppRiskLevel,
        threatProofConfidence: Int,
    ): Int {
        return confidenceFor(
            status = statusFor(score, riskLevel, threatProofConfidence),
            score = score.coerceIn(0, 100),
            threatProofConfidence = threatProofConfidence.coerceIn(0, 100),
        )
    }

    fun statusFor(
        score: Int,
        riskLevel: AppRiskLevel,
        threatProofConfidence: Int,
    ): AppSemanticVerdictStatus {
        val scoreBand = scoreBandFor(score)
        val proofBand = AppSemanticProofLevel.from(threatProofConfidence)
        return when (scoreBand) {
            AppRiskLevel.CLEAN -> if (threatProofConfidence == 0) {
                AppSemanticVerdictStatus.PROVEN_CLEAN
            } else {
                AppSemanticVerdictStatus.INCONSISTENT
            }
            AppRiskLevel.LOW -> if (proofBand == AppSemanticProofLevel.LOW) {
                AppSemanticVerdictStatus.PROVEN_LOW_RISK
            } else {
                AppSemanticVerdictStatus.INCONSISTENT
            }
            AppRiskLevel.MEDIUM -> when (proofBand) {
                AppSemanticProofLevel.LOW -> AppSemanticVerdictStatus.UNPROVEN_THREAT
                AppSemanticProofLevel.MEDIUM -> AppSemanticVerdictStatus.PARTIAL_THREAT
                AppSemanticProofLevel.HIGH -> AppSemanticVerdictStatus.INCONSISTENT
            }
            AppRiskLevel.HIGH,
            AppRiskLevel.CRITICAL,
            -> when (proofBand) {
                AppSemanticProofLevel.LOW -> AppSemanticVerdictStatus.UNPROVEN_THREAT
                AppSemanticProofLevel.MEDIUM -> AppSemanticVerdictStatus.PARTIAL_THREAT
                AppSemanticProofLevel.HIGH -> AppSemanticVerdictStatus.PROVEN_THREAT
            }
        }
    }

    private fun scoreBandFor(score: Int): AppRiskLevel {
        return when (score.coerceIn(0, 100)) {
            0 -> AppRiskLevel.CLEAN
            in 1..19 -> AppRiskLevel.LOW
            in 20..49 -> AppRiskLevel.MEDIUM
            in 50..89 -> AppRiskLevel.HIGH
            else -> AppRiskLevel.CRITICAL
        }
    }

    private fun confidenceFor(
        status: AppSemanticVerdictStatus,
        score: Int,
        threatProofConfidence: Int,
    ): Int {
        return when (status) {
            AppSemanticVerdictStatus.PROVEN_CLEAN -> 100
            AppSemanticVerdictStatus.PROVEN_LOW_RISK -> (100 - score).coerceIn(0, 100)
            AppSemanticVerdictStatus.UNPROVEN_THREAT -> maxOf(score, 100 - threatProofConfidence).coerceIn(0, 100)
            AppSemanticVerdictStatus.PARTIAL_THREAT,
            AppSemanticVerdictStatus.PROVEN_THREAT,
            -> threatProofConfidence
            AppSemanticVerdictStatus.INCONSISTENT -> minOf(score, threatProofConfidence)
        }
    }
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
    val proofConfidence: Int = confidence.coerceIn(0, 100),
    val proofLevel: AppSemanticProofLevel = AppSemanticProofLevel.from(proofConfidence),
    val proofReason: String = "",
)

data class AppSemanticRiskBucket(
    val score: Int = 0,
    val riskLevel: AppRiskLevel = AppRiskLevel.CLEAN,
    val signals: List<AppSemanticSignal> = emptyList(),
    val proofConfidence: Int = signals.maxOfOrNull(AppSemanticSignal::proofConfidence) ?: 0,
    val proofLevel: AppSemanticProofLevel = AppSemanticProofLevel.from(proofConfidence),
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
    val proofConfidence: Int = signals.maxOfOrNull(AppSemanticSignal::proofConfidence) ?: 0,
    val proofLevel: AppSemanticProofLevel = AppSemanticProofLevel.from(proofConfidence),
    val verdictConfidence: Int = AppSemanticVerdictConfidence.from(
        score = score,
        riskLevel = riskLevel,
        threatProofConfidence = proofConfidence,
    ),
    val verdictLevel: AppSemanticProofLevel = AppSemanticProofLevel.from(verdictConfidence),
    val verdictStatus: AppSemanticVerdictStatus = AppSemanticVerdictConfidence.statusFor(
        score = score,
        riskLevel = riskLevel,
        threatProofConfidence = proofConfidence,
    ),
) {
    val hasWarnings: Boolean
        get() = riskLevel != AppRiskLevel.CLEAN
}
