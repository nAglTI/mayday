package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.SplitTunnelMode
import org.debs.mayday.core.model.UiPreferences
import org.debs.mayday.core.model.VpnMetricsConfig
import org.debs.mayday.core.model.VpnTransportMode

data class SettingsUiState(
    val uiPreferences: UiPreferences = UiPreferences(),
    val relays: List<RelayDraft> = listOf(RelayDraft()),
    val userId: String = "",
    val servers: List<ServerDraft> = listOf(ServerDraft()),
    val tunName: String = "",
    val dnsServers: String = "1.1.1.1",
    val mtu: String = "1280",
    val serverFailbackDelaySec: String = "60",
    val transportMode: VpnTransportMode = VpnTransportMode.AUTO,
    val transportOptions: List<TransportModeOption> = defaultTransportModeOptions(),
    val prestartFullProbe: Boolean = false,
    val steadyStateQuickProbeEnabled: Boolean = false,
    val steadyStateBenchmarkEnabled: Boolean = false,
    val networkRescueProfile: NetworkRescueProfile = NetworkRescueProfile.OFF,
    val disableIpv6: Boolean = false,
    val packetFragmentPayloadBytes: String = "0",
    val disablePacketBatching: Boolean = false,
    val metrics: VpnMetricsConfig = VpnMetricsConfig(),
    val autoReconnect: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val selectedPackageCount: Int = 0,
    val isLoading: Boolean = true,
    val hasUnsavedChanges: Boolean = false,
    val importedConfigName: String? = null,
    val preservedConfigJson: String = "",
    val message: String? = null,
) {
    val themeMode: AppThemeMode get() = uiPreferences.themeMode
    val language: AppLanguage get() = uiPreferences.language
    val density: AppDensity get() = uiPreferences.density
}

data class TransportModeOption(
    val mode: VpnTransportMode,
    val label: String,
)

fun defaultTransportModeOptions(): List<TransportModeOption> {
    return listOf(
        VpnTransportMode.AUTO,
        VpnTransportMode.UTP,
        VpnTransportMode.WS,
        VpnTransportMode.HTTPS,
        VpnTransportMode.TCP,
        VpnTransportMode.RAW_UDP,
    ).map { mode ->
        TransportModeOption(
            mode = mode,
            label = mode.runtimeId,
        )
    }
}
