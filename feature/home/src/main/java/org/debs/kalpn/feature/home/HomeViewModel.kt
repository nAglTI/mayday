package org.debs.kalpn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.debs.kalpn.core.data.repository.VpnProfileRepository
import org.debs.kalpn.core.model.SplitTunnelMode
import org.debs.kalpn.core.vpn.controller.VpnConnectionController
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: VpnProfileRepository,
    private val connectionController: VpnConnectionController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.profile,
        connectionController.state,
    ) { profile, runtime ->
        HomeUiState(
            status = runtime.status,
            headline = runtime.headline,
            detail = runtime.detail,
            engineAvailable = runtime.engineAvailable,
            engineDiagnostics = runtime.engineDiagnostics,
            endpointSummary = profile.endpointSummary(),
            userId = profile.userId.ifBlank { "Not set" },
            serverSummary = if (profile.servers.isEmpty()) {
                "No servers configured"
            } else {
                "${profile.servers.size} server(s): " + profile.servers.joinToString { it.id }
            },
            splitTunnelSummary = when (profile.splitTunnelMode) {
                SplitTunnelMode.DISABLED -> "Split tunnel disabled"
                SplitTunnelMode.ONLY_SELECTED ->
                    "Only ${profile.selectedPackages.size} selected apps use VPN"
                SplitTunnelMode.EXCLUDE_SELECTED ->
                    "${profile.selectedPackages.size} selected apps bypass VPN"
            },
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
