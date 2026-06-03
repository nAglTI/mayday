package org.debs.mayday.feature.onboarding

sealed interface OnboardingUiEvent {
    data object ImportClipboardClicked : OnboardingUiEvent
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
    data object ImportFromClipboard : OnboardingUiEffect
    data object NavigateHome : OnboardingUiEffect
}
