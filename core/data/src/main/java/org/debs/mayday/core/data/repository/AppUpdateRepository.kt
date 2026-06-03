package org.debs.mayday.core.data.repository

import org.debs.mayday.core.model.AppUpdateInfo

interface AppUpdateRepository {
    suspend fun checkForUpdate(): AppUpdateInfo?
}
