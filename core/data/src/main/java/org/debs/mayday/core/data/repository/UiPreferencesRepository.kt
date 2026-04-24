package org.debs.mayday.core.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences

interface UiPreferencesRepository {
    val preferences: StateFlow<UiPreferences>

    suspend fun setThemeMode(themeMode: AppThemeMode)

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setDensity(density: AppDensity)

    suspend fun setOnboardingCompleted(completed: Boolean)
}
