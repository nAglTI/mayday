package org.debs.mayday.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: VpnProfileRepository,
    uiPreferencesRepository: UiPreferencesRepository,
    private val connectionController: VpnConnectionController,
) : ViewModel() {

    private val effectChannel = Channel<HomeUiEffect>(Channel.BUFFERED)
    val effect: Flow<HomeUiEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.profile,
        uiPreferencesRepository.preferences,
        connectionController.state,
    ) { profile, uiPreferences, runtime ->
        HomeUiState(
            uiPreferences = uiPreferences,
            status = runtime.status,
            headline = runtime.headline,
            detail = runtime.detail,
            engineAvailable = runtime.engineAvailable,
            engineDiagnostics = runtime.engineDiagnostics,
            profileName = profile.profileName,
            endpointSummary = profile.endpointSummary(),
            primaryServerId = profile.servers.firstOrNull()?.id.orEmpty(),
            userId = profile.userId,
            serverCount = profile.servers.size,
            splitTunnelMode = profile.splitTunnelMode,
            selectedPackageCount = profile.selectedPackages.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )

    fun onEvent(event: HomeUiEvent) {
        when (event) {
            HomeUiEvent.ConnectClicked -> emitEffect(HomeUiEffect.RequestStartFlow)
            HomeUiEvent.DisconnectClicked -> connectionController.stop()
            HomeUiEvent.SettingsClicked -> emitEffect(HomeUiEffect.NavigateToSettings)
            HomeUiEvent.StartConfirmed -> connectionController.start()
        }
    }

    private fun emitEffect(effect: HomeUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }
}
