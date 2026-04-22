package org.debs.mayday.feature.onboarding

import org.debs.mayday.core.model.UiPreferences

data class OnboardingUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val isLoading: Boolean = true,
    val message: String? = null,
    val navigationTarget: OnboardingNavigationTarget? = null,
)
