package org.debs.mayday.core.model

data class UiPreferences(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val language: AppLanguage = AppLanguage.EN,
    val density: AppDensity = AppDensity.COMFORTABLE,
    val onboardingCompleted: Boolean = false,
)
