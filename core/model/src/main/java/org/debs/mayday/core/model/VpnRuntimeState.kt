package org.debs.mayday.core.model

data class VpnRuntimeState(
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "",
    val detail: String = "",
    val engineAvailable: Boolean = false,
    val activeProfileSummary: String = "",
    val engineDiagnostics: String? = null,
)
