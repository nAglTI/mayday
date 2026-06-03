package org.debs.mayday.core.model

enum class AppRiskLevel {
    CLEAN,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class AppRiskFindingType {
    ANDROID_API,
    NETWORK_INTERFACE,
    PROXY,
    LINUX_PROC,
    VPN_APP_DISCOVERY,
    TOR,
    TELEMETRY,
    PACKAGE_VISIBILITY,
    NETWORK_PERMISSION,
    ROUTING,
    DNS,
    LOCAL_PROXY,
    XRAY_API,
    CLASH_API,
    PUBLIC_IP,
    BYPASS,
    ACTIVE_VPN,
    COMBINED,
    NETWORK_LIBRARY,
}

enum class AppRiskSignalStrength {
    LOW,
    MEDIUM,
    HIGH,
}

data class AppRiskMatchedSignal(
    val indicator: String,
    val evidence: String,
)

data class AppRiskFinding(
    val type: AppRiskFindingType,
    val indicator: String,
    val strength: AppRiskSignalStrength,
    val evidence: String,
    val score: Int,
    val matchedSignals: List<AppRiskMatchedSignal> = emptyList(),
    val relatedIndicators: List<String> = emptyList(),
    val description: String = "",
)

data class AppRiskScanResult(
    val riskScore: Int = 0,
    val riskLevel: AppRiskLevel = AppRiskLevel.CLEAN,
    val findings: List<AppRiskFinding> = emptyList(),
    val knownGroup: String? = null,
    val knownAppName: String? = null,
    val knownStatus: String? = null,
    val scannedAtEpochMillis: Long = 0L,
) {
    val hasWarnings: Boolean
        get() = riskLevel != AppRiskLevel.CLEAN
}
