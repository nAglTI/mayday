package org.debs.mayday.core.data.repository

import kotlinx.coroutines.flow.Flow
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences

interface UiPreferencesRepository {
    val preferences: Flow<UiPreferences>

    suspend fun setThemeMode(themeMode: AppThemeMode)

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setDensity(density: AppDensity)

    suspend fun setOnboardingCompleted(completed: Boolean)
}
