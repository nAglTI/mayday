package org.debs.mayday.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.debs.mayday.core.data.repository.AppUpdateRepository
import org.debs.mayday.core.data.repository.UiPreferencesRepository
import org.debs.mayday.core.data.repository.VpnProfileRepository
import org.debs.mayday.core.designsystem.theme.MaydayStrings
import org.debs.mayday.core.designsystem.theme.maydayStrings
import org.debs.mayday.core.designsystem.theme.relayCountLabel
import org.debs.mayday.core.designsystem.theme.serverCountLabel
import org.debs.mayday.core.model.AppUpdateInfo
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import org.debs.mayday.core.model.VpnProfile
import org.debs.mayday.core.model.VpnProfileCompatibilityValidator
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: VpnProfileRepository,
    uiPreferencesRepository: UiPreferencesRepository,
    private val connectionController: VpnConnectionController,
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {

    private val effectChannel = Channel<HomeUiEffect>(Channel.BUFFERED)
    val effect: Flow<HomeUiEffect> = effectChannel.receiveAsFlow()
    private val availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.profile,
        uiPreferencesRepository.preferences,
        connectionController.state,
        availableUpdate,
    ) { profile, uiPreferences, runtime, updateInfo ->
        val strings = maydayStrings(uiPreferences.language)
        HomeUiState(
            uiPreferences = uiPreferences,
            status = runtime.status,
            headline = runtime.headline,
            detail = runtime.detail,
            engineAvailable = runtime.engineAvailable,
            engineDiagnostics = runtime.engineDiagnostics,
            coreState = runtime.coreState,
            vpnState = runtime.vpnState,
            activeRelayId = runtime.activeRelayId,
            activeTransportId = runtime.activeTransportId,
            activeTransportLabel = runtime.activeTransportLabel,
            activeServerId = runtime.activeServerId,
            uploadBps = runtime.uploadBps,
            downloadBps = runtime.downloadBps,
            aggregateBps = runtime.aggregateBps,
            protocolDiagnostics = runtime.protocolDiagnostics,
            endpointDiagnostics = runtime.endpointDiagnostics,
            endpointSummary = profile.localizedEndpointSummary(strings),
            primaryServerId = profile.servers.firstOrNull()?.id.orEmpty(),
            userId = profile.userId,
            serverCount = profile.servers.size,
            splitTunnelMode = profile.splitTunnelMode,
            selectedPackageCount = profile.selectedPackages.size,
            availableUpdate = updateInfo,
            profileCompatibilityIssue = VpnProfileCompatibilityValidator.firstIssue(profile),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(uiPreferences = uiPreferencesRepository.preferences.value),
    )

    init {
        viewModelScope.launch {
            appUpdateRepository.checkForUpdate()?.let { updateInfo ->
                availableUpdate.value = updateInfo
            }
        }
    }

    fun onEvent(event: HomeUiEvent) {
        when (event) {
            HomeUiEvent.ConnectClicked -> emitEffect(HomeUiEffect.RequestStartFlow)
            HomeUiEvent.DisconnectClicked -> connectionController.stop()
            HomeUiEvent.SettingsClicked -> emitEffect(HomeUiEffect.NavigateToSettings)
            HomeUiEvent.StartConfirmed -> connectionController.start()
            HomeUiEvent.UpdateClicked -> {
                availableUpdate.value?.releaseUrl?.let { url ->
                    emitEffect(HomeUiEffect.OpenUrl(url))
                }
            }
            HomeUiEvent.UpdateDismissed -> {
                availableUpdate.value = null
            }
        }
    }

    private fun emitEffect(effect: HomeUiEffect) {
        viewModelScope.launch {
            effectChannel.send(effect)
        }
    }

    private fun VpnProfile.localizedEndpointSummary(strings: MaydayStrings): String {
        if (relays.isEmpty()) {
            return ""
        }
        return buildString {
            append(strings.relayCountLabel(relays.size))
            if (servers.isNotEmpty()) {
                append(" -> ")
                append(servers.first().id)
                append(" (")
                append(strings.serverCountLabel(servers.size))
                append(")")
            }
        }
    }
}
