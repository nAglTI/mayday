package org.debs.mayday.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.UiPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultUiPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
) : UiPreferencesRepository {
    private val defaultLanguage = resolveSystemLanguage(context)

    override val preferences: StateFlow<UiPreferences> = dataStore.data
        .map { preferences ->
            UiPreferences(
                themeMode = AppThemeMode.entries.getOrElse(
                    preferences[THEME_MODE] ?: AppThemeMode.DARK.ordinal,
                ) { AppThemeMode.DARK },
                language = AppLanguage.entries.getOrElse(
                    preferences[LANGUAGE] ?: defaultLanguage.ordinal,
                ) { defaultLanguage },
                density = AppDensity.entries.getOrElse(
                    preferences[DENSITY] ?: AppDensity.COMFORTABLE.ordinal,
                ) { AppDensity.COMFORTABLE },
                onboardingCompleted = preferences[ONBOARDING_COMPLETED] ?: false,
                hiddenRiskPackages = preferences[HIDDEN_RISK_PACKAGES].orEmpty(),
            )
        }
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.Eagerly,
            initialValue = UiPreferences(language = defaultLanguage),
        )

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

    override suspend fun setRiskWarningHidden(packageName: String, hidden: Boolean) {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isBlank()) return
        dataStore.edit { preferences ->
            val current = preferences[HIDDEN_RISK_PACKAGES].orEmpty()
            preferences[HIDDEN_RISK_PACKAGES] = if (hidden) {
                current + normalizedPackageName
            } else {
                current - normalizedPackageName
            }
        }
    }

    private companion object {
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val THEME_MODE = intPreferencesKey("ui_theme_mode")
        val LANGUAGE = intPreferencesKey("ui_language")
        val DENSITY = intPreferencesKey("ui_density")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("ui_onboarding_completed")
        val HIDDEN_RISK_PACKAGES = stringSetPreferencesKey("ui_hidden_risk_packages")

        fun resolveSystemLanguage(context: Context): AppLanguage {
            val language = context.resources.configuration.locales[0]?.language.orEmpty()
            return if (language.equals("ru", ignoreCase = true)) {
                AppLanguage.RU
            } else {
                AppLanguage.EN
            }
        }
    }
}
