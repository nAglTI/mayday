package org.debs.mayday.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnConfigImportParser
import org.debs.mayday.core.data.repository.VpnProfileRepository
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val profileRepository: VpnProfileRepository,
    private val configImportParser: VpnConfigImportParser,
) : ViewModel() {

    private val mutableState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    private var initialRedirectConsumed = false

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                val shouldAutoRedirect = preferences.onboardingCompleted && !initialRedirectConsumed
                if (shouldAutoRedirect) {
                    initialRedirectConsumed = true
                }
                mutableState.update { state ->
                    state.copy(
                        uiPreferences = preferences,
                        isLoading = false,
                        navigationTarget = when {
                            state.navigationTarget != null -> state.navigationTarget
                            shouldAutoRedirect -> OnboardingNavigationTarget.HOME
                            else -> null
                        },
                    )
                }
            }
        }
    }

    fun importConfig(rawConfig: String, sourceName: String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, message = null) }
            runCatching {
                val parsed = configImportParser.parse(rawConfig, currentProfileName = "Primary")
                profileRepository.save(parsed)
                uiPreferencesRepository.setOnboardingCompleted(true)
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        message = "Imported ${sourceName ?: "config"}",
                        navigationTarget = OnboardingNavigationTarget.HOME,
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        message = error.message ?: "Failed to import the selected file.",
                    )
                }
            }
        }
    }

    fun openManualSetup() {
        viewModelScope.launch {
            uiPreferencesRepository.setOnboardingCompleted(true)
            mutableState.update { it.copy(navigationTarget = OnboardingNavigationTarget.SETTINGS) }
        }
    }

    fun continueWithoutImport() {
        viewModelScope.launch {
            uiPreferencesRepository.setOnboardingCompleted(true)
            mutableState.update { it.copy(navigationTarget = OnboardingNavigationTarget.HOME) }
        }
    }

    fun showMessage(message: String) {
        mutableState.update { it.copy(message = message, isLoading = false) }
    }

    fun onMessageConsumed() {
        mutableState.update { it.copy(message = null) }
    }

    fun onNavigationHandled() {
        mutableState.update { it.copy(navigationTarget = null) }
    }
}
