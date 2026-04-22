package org.debs.mayday.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
            userId = profile.userId.ifBlank { "not set" },
            serverCount = profile.servers.size,
            splitTunnelMode = profile.splitTunnelMode,
            selectedPackageCount = profile.selectedPackages.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun startVpn() {
        connectionController.start()
    }

    fun stopVpn() {
        connectionController.stop()
    }
}
