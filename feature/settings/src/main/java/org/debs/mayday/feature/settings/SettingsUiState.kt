package org.debs.mayday.feature.settings

import org.debs.mayday.core.model.AppDensity
import org.debs.mayday.core.model.AppLanguage
import org.debs.mayday.core.model.AppThemeMode
import org.debs.mayday.core.model.NetworkRescueProfile
import org.debs.mayday.core.model.PacketPaddingMode
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
    val packetPaddingMode: PacketPaddingMode = PacketPaddingMode.OFF,
    val packetPaddingMinBytes: String = "0",
    val packetPaddingMaxBytes: String = "0",
    val lastValidPacketPaddingRange: Pair<Int, Int> = 0 to 0,
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
