package org.debs.mayday.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnConfigImportParser
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.importedConfigMessage
import org.debs.mayday.core.designsystem.theme.maydayStrings
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val profileRepository: VpnProfileRepository,
    private val configImportParser: VpnConfigImportParser,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        OnboardingUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )
    val uiState: StateFlow<OnboardingUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<OnboardingUiEffect>(Channel.BUFFERED)
    val effect: Flow<OnboardingUiEffect> = effectChannel.receiveAsFlow()

    private var initialRedirectConsumed = false

    init {
        viewModelScope.launch {
            uiPreferencesRepository.preferences.collectLatest { preferences ->
                val shouldAutoRedirect = preferences.onboardingCompleted && !initialRedirectConsumed
                if (shouldAutoRedirect) {
                    initialRedirectConsumed = true
                    effectChannel.send(OnboardingUiEffect.NavigateHome)
                }
                mutableState.update { state ->
                    state.copy(
                        uiPreferences = preferences,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onEvent(event: OnboardingUiEvent) {
        when (event) {
            OnboardingUiEvent.ImportClicked -> emitEffect(OnboardingUiEffect.OpenConfigPicker)
            OnboardingUiEvent.ManualSetupClicked -> completeOnboardingAndNavigate(
                OnboardingUiEffect.NavigateToSettings,
            )
            OnboardingUiEvent.ContinueClicked -> completeOnboardingAndNavigate(
                OnboardingUiEffect.NavigateHome,
            )
            OnboardingUiEvent.MessageShown -> {
                mutableState.update { it.copy(message = null) }
            }
            is OnboardingUiEvent.ConfigSelected -> importConfig(
                rawConfig = event.rawConfig,
                sourceName = event.sourceName,
            )
            is OnboardingUiEvent.ImportSelectionFailed -> {
                mutableState.update {
                    it.copy(message = event.message, isLoading = false)
                }
            }
        }
    }

    private fun importConfig(rawConfig: String, sourceName: String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, message = null) }
            runCatching {
                val parsed = configImportParser.parse(rawConfig, currentProfileName = strings().profile)
                profileRepository.save(parsed)
                initialRedirectConsumed = true
                uiPreferencesRepository.setOnboardingCompleted(true)
            }.onSuccess {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        message = strings().importedConfigMessage(sourceName.orEmpty()),
                    )
                }
                effectChannel.send(OnboardingUiEffect.NavigateHome)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        message = error.message ?: strings().failedImportSelectedFile,
                    )
                }
            }
        }
    }

    private fun completeOnboardingAndNavigate(effect: OnboardingUiEffect) {
        viewModelScope.launch {
            initialRedirectConsumed = true
            uiPreferencesRepository.setOnboardingCompleted(true)
            effectChannel.send(effect)
        }
    }

    private fun emitEffect(effect: OnboardingUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun strings() = maydayStrings(uiState.value.uiPreferences.language)
}
