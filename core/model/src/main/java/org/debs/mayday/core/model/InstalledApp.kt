package org.debs.mayday.core.model

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val risk: AppRiskScanResult = AppRiskScanResult(),
)
