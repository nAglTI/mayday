package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.InstalledApp
import org.debs.mayday.core.model.AppRiskScanResult

interface InstalledAppsRepository {
    suspend fun getInstalledApps(): List<InstalledApp>

    suspend fun scanAppRisk(
        packageName: String,
        force: Boolean = false,
    ): AppRiskScanResult?
}
