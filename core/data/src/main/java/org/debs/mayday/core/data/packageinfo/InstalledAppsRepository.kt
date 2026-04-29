package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.InstalledApp

interface InstalledAppsRepository {
    suspend fun getInstalledApps(): List<InstalledApp>
}
