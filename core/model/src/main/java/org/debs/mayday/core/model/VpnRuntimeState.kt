package org.debs.mayday.core.model

data class VpnRuntimeState(
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "",
    val detail: String = "",
    val engineAvailable: Boolean = false,
    val activeProfileSummary: String = "",
    val engineDiagnostics: String? = null,
    val coreState: String = "",
    val vpnState: String = "",
    val activeRelayId: String = "",
    val activeTransportId: String = "",
    val activeTransportLabel: String = "",
    val activeServerId: String = "",
    val uploadBps: Double = 0.0,
    val downloadBps: Double = 0.0,
    val aggregateBps: Double = 0.0,
    val protocolDiagnostics: List<String> = emptyList(),
    val endpointDiagnostics: List<String> = emptyList(),
)
