package org.debs.mayday.feature.onboarding

sealed interface OnboardingUiEvent {
    data object ImportClicked : OnboardingUiEvent
    data object ManualSetupClicked : OnboardingUiEvent
    data object ContinueClicked : OnboardingUiEvent
    data object MessageShown : OnboardingUiEvent
    data class ConfigSelected(
        val rawConfig: String,
        val sourceName: String?,
    ) : OnboardingUiEvent
    data class ImportSelectionFailed(
        val message: String,
    ) : OnboardingUiEvent
}

sealed interface OnboardingUiEffect {
    data object OpenConfigPicker : OnboardingUiEffect
    data object NavigateHome : OnboardingUiEffect
    data object NavigateToSettings : OnboardingUiEffect
}
