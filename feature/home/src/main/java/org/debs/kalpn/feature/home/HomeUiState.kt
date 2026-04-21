package org.debs.kalpn.feature.home

import org.debs.kalpn.core.model.VpnConnectionStatus

data class HomeUiState(
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "",
    val detail: String = "",
    val engineAvailable: Boolean = false,
    val engineDiagnostics: String? = null,
    val endpointSummary: String = "Relay not configured",
    val userId: String = "",
    val serverSummary: String = "No servers configured",
    val splitTunnelSummary: String = "Split tunnel disabled",
)
