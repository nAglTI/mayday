package org.debs.kalpn.core.data.packageinfo

import org.debs.kalpn.core.model.InstalledApp

interface InstalledAppsRepository {
    suspend fun getInstalledApps(): List<InstalledApp>
}
