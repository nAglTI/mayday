package org.debs.kalpn.core.model

data class VpnRuntimeState(
    val status: VpnConnectionStatus = VpnConnectionStatus.Idle,
    val headline: String = "VPN shell idle",
    val detail: String = "Configure relay and split tunnel rules, then request VPN permission.",
    val engineAvailable: Boolean = false,
    val activeProfileSummary: String = "No profile selected",
    val engineDiagnostics: String? = null,
)
