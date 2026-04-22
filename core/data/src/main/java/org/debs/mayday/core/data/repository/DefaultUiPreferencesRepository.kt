package org.debs.mayday.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultUiPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UiPreferencesRepository {

    override val preferences: Flow<UiPreferences> = dataStore.data.map { preferences ->
        UiPreferences(
            themeMode = AppThemeMode.entries.getOrElse(
                preferences[THEME_MODE] ?: AppThemeMode.DARK.ordinal,
            ) { AppThemeMode.DARK },
            language = AppLanguage.entries.getOrElse(
                preferences[LANGUAGE] ?: AppLanguage.EN.ordinal,
            ) { AppLanguage.EN },
            density = AppDensity.entries.getOrElse(
                preferences[DENSITY] ?: AppDensity.COMFORTABLE.ordinal,
            ) { AppDensity.COMFORTABLE },
            onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: false,
        )
    }

    override suspend fun setThemeMode(themeMode: AppThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE] = themeMode.ordinal
        }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { preferences ->
            preferences[LANGUAGE] = language.ordinal
        }
    }

    override suspend fun setDensity(density: AppDensity) {
        dataStore.edit { preferences ->
            preferences[DENSITY] = density.ordinal
        }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
        }
    }

    private companion object {
        val THEME_MODE = intPreferencesKey("ui_theme_mode")
        val LANGUAGE = intPreferencesKey("ui_language")
        val DENSITY = intPreferencesKey("ui_density")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("ui_onboarding_completed")
    }
}
